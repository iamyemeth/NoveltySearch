package nu.marginalia.converting.processor;

import com.google.inject.Inject;
import lombok.SneakyThrows;
import nu.marginalia.atags.AnchorTextKeywords;
import nu.marginalia.atags.model.DomainLinks;
import nu.marginalia.atags.source.AnchorTagsSource;
import nu.marginalia.atags.source.AnchorTagsSourceFactory;
import nu.marginalia.converting.model.ProcessedDocument;
import nu.marginalia.converting.processor.logic.links.LinkGraph;
import nu.marginalia.converting.sideload.SideloadSource;
import nu.marginalia.converting.writer.ConverterBatchWritableIf;
import nu.marginalia.converting.writer.ConverterBatchWriter;
import nu.marginalia.crawling.io.SerializableCrawlDataStream;
import nu.marginalia.crawling.model.*;
import nu.marginalia.geoip.GeoIpDictionary;
import nu.marginalia.geoip.sources.AsnTable;
import nu.marginalia.model.crawl.DomainIndexingState;
import nu.marginalia.converting.model.ProcessedDomain;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.converting.processor.logic.links.TopKeywords;
import nu.marginalia.converting.processor.logic.LshDocumentDeduplicator;
import nu.marginalia.util.ProcessingIterator;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

public class DomainProcessor {
    private final DocumentProcessor documentProcessor;
    private final SiteWords siteWords;
    private final AnchorTagsSource anchorTagsSource;
    private final AnchorTextKeywords anchorTextKeywords;
    private final GeoIpDictionary geoIpDictionary;


    // The threshold for running a cheaper sideloading-style process
    // (10 MB is ~ 99.5%th percentile of domain data sizes)
    private static final long DOMAIN_SIDELOAD_THRESHOLD = 10_000_000L;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Inject
    public DomainProcessor(DocumentProcessor documentProcessor,
                           SiteWords siteWords,
                           AnchorTagsSourceFactory anchorTagsSourceFactory,
                           AnchorTextKeywords anchorTextKeywords,
                           GeoIpDictionary geoIpDictionary) throws SQLException
    {
        this.documentProcessor = documentProcessor;
        this.siteWords = siteWords;
        this.anchorTextKeywords = anchorTextKeywords;
        this.anchorTagsSource = anchorTagsSourceFactory.create();
        this.geoIpDictionary = geoIpDictionary;

        geoIpDictionary.waitReady();
    }

    public ConverterBatchWritableIf createWritable(SerializableCrawlDataStream domain) throws IOException {
        Path filePath = domain.path();

        if (filePath != null && Files.size(filePath) > DOMAIN_SIDELOAD_THRESHOLD) {
            // If the file is too big, we run a processing mode that doesn't
            // require loading the entire dataset into RAM
            return sideloadProcessing(domain);
        }

        return fullProcessing(domain);
    }

    public SideloadProcessing sideloadProcessing(SerializableCrawlDataStream dataStream) {
        try {
            return new SideloadProcessing(dataStream);
        }
        catch (Exception ex) {
            logger.warn("Failed to process domain sideload", ex);
            return null;
        }

    }

    public class SideloadProcessing implements ConverterBatchWritableIf, SideloadSource {
        private final SerializableCrawlDataStream dataStream;
        private final ProcessedDomain domain;
        private final DocumentDecorator documentDecorator;
        private final Set<String> processedUrls = new HashSet<>();
        private final DomainLinks externalDomainLinks;
        private final LshDocumentDeduplicator deduplicator = new LshDocumentDeduplicator();

        SideloadProcessing(SerializableCrawlDataStream dataStream) throws IOException {
            this.dataStream = dataStream;

            if (!dataStream.hasNext()
             || !(dataStream.next() instanceof CrawledDomain crawledDomain))
            {
                throw new IllegalStateException("First record must be a domain");
            }

            domain = new ProcessedDomain();
            externalDomainLinks = anchorTagsSource.getAnchorTags(domain.domain);
            documentDecorator = new DocumentDecorator(anchorTextKeywords, externalDomainLinks);

            processDomain(crawledDomain, domain, documentDecorator);
        }

        @Override
        public ProcessedDomain getDomain() {
            return domain;
        }

        @Override
        public Iterator<ProcessedDocument> getDocumentsStream() {
            return new DocumentsIterator();
        }

        class DocumentsIterator implements Iterator<ProcessedDocument> {
            ProcessedDocument next = null;
            @Override
            public boolean hasNext() {
                try {
                    while (next == null
                            && dataStream.hasNext())
                    {
                        if (!(dataStream.next() instanceof CrawledDocument doc))
                            continue;
                        if (doc.url == null || !processedUrls.add(doc.url))
                            continue;

                        var processedDoc = documentProcessor.process(doc, externalDomainLinks, documentDecorator);

                        deduplicator.markIfDuplicate(processedDoc);
                        next = processedDoc;

                        if (processedDoc.isProcessedFully()) {
                            // This is a bit sketchy, but we need to set the size and topology to something
                            processedDoc.details.metadata = processedDoc.details.metadata.withSizeAndTopology(
                                    10_000, externalDomainLinks.countForUrl(processedDoc.url));
                        }

                        return true;
                    }
                }
                catch (IOException ex) {
                    logger.warn("Failed to process domain sideload", ex);
                }

                return false;
            }

            @Override
            public ProcessedDocument next() {
                try {
                    if (next == null && !hasNext())
                        throw new NoSuchElementException();
                    return next;
                } finally {
                    next = null;
                }
            }
        }

        @Override
        public void write(ConverterBatchWriter writer) throws IOException {
            writer.writeSideloadSource(this);
        }

        @Override
        public String id() {
            return domain.domain.toString();
        }

        @Override
        public void close() throws Exception {
            dataStream.close();
            deduplicator.close();
        }
    }


    @SneakyThrows
    @Nullable
    public ProcessedDomain fullProcessing(SerializableCrawlDataStream dataStream) {
        if (!dataStream.hasNext()) {
            return null;
        }

        ProcessedDomain ret = new ProcessedDomain();
        List<ProcessedDocument> docs = new ArrayList<>();
        Set<String> processedUrls = new HashSet<>();

        DomainLinks externalDomainLinks = null;

        DocumentDecorator documentDecorator = null;

        try (var deduplicator = new LshDocumentDeduplicator()){
            while (dataStream.hasNext()) {
                var data = dataStream.next();

                // Do a lazy load of the external domain links since we don't know the domain
                // until we see the first document
                if (externalDomainLinks == null) {
                    var domain = data.getDomain();

                    if (domain != null) {
                        externalDomainLinks = anchorTagsSource.getAnchorTags(domain);
                    }
                }

                if (data instanceof CrawledDomain crawledDomain) {
                    documentDecorator = new DocumentDecorator(anchorTextKeywords, externalDomainLinks);

                    processDomain(crawledDomain, ret, documentDecorator);
                    ret.documents = docs;

                } else if (data instanceof CrawledDocument doc) {
                    try {
                        if (doc.url == null || !processedUrls.add(doc.url))
                            continue;

                        var processedDoc = documentProcessor.process(doc, externalDomainLinks, documentDecorator);

                        deduplicator.markIfDuplicate(processedDoc);

                        docs.add(processedDoc);
                    } catch (Exception ex) {
                        logger.warn("Failed to process " + doc.url, ex);
                    }
                }
            }

        }

        // Add late keywords and features from domain-level information

        calculateStatistics(ret, externalDomainLinks);

        return ret;
    }

    private void processDomain(CrawledDomain crawledDomain,
                                          ProcessedDomain domain,
                                          DocumentDecorator decorator)
    {
        domain.domain = new EdgeDomain(crawledDomain.domain);
        domain.ip = crawledDomain.ip;

        addIpInfo(decorator, crawledDomain.ip);

        if (isAcademicDomain(domain.domain)) {
            decorator.addTerm("special:academia");
        }

        if (crawledDomain.redirectDomain != null) {
            domain.redirect = new EdgeDomain(crawledDomain.redirectDomain);
        }
        domain.state = getState(crawledDomain.crawlerStatus);
    }


    private void addIpInfo(DocumentDecorator decorator, String ip) {
        decorator.addTerm("ip:"+ip);

        // Add IP location country as a term
        String country = geoIpDictionary.getCountry(ip);
        if (!country.isBlank()) { // use the ip:-prefix as there's no real confusion between e.g. ip:127.0.0.1 and ip:uk
            decorator.addTerm("ip:"+country.toLowerCase());
        }

        // Add ASN as a term
        geoIpDictionary.getAsnInfo(ip).ifPresent(asnInfo -> {
            decorator.addTerm("as:"+asnInfo.asn());

            for (var orgPart : StringUtils.split(asnInfo.org(), '-')) {
                decorator.addTerm("as:"+orgPart.toLowerCase());
            }

            if (isCloudy(asnInfo)) {
                decorator.addTerm("special:cloud");
            }
        });


    }

    private boolean isCloudy(AsnTable.AsnInfo asnInfo) {
        String org = asnInfo.org();

        if (org.contains("MICROSOFT-AZURE")) {
            return true;
        }
        if(org.contains("AMAZON")) {
            return true;
        }
        if (org.contains("CLOUDFLARE")) {
            return true;
        }
        if (org.contains("GOOGLE-CLOUD")) {
            return true;
        }
        if (org.contains("DIGITALOCEAN")) {
            return true;
        }
        if (org.contains("ALIBABA")) {
            return true;
        }

        return false;
    }


    private static final Pattern academicPattern = Pattern.compile(".*\\.(ac|edu)\\.[a-z]{2}$");
    private boolean isAcademicDomain(EdgeDomain domain) {

        if (domain.topDomain.endsWith(".edu"))
            return true;

        if (academicPattern.matcher(domain.topDomain).matches())
            return true;

        return false;
    }

    private void calculateStatistics(ProcessedDomain ret, DomainLinks externalDomainLinks) {
        LinkGraph linkGraph = new LinkGraph();
        TopKeywords topKeywords = new TopKeywords();

        ret.documents.forEach(topKeywords::accept);
        ret.documents.forEach(linkGraph::add);

        var invertedLinkGraph = linkGraph.invert();

        ret.documents.forEach(doc -> {
            if (doc.details == null)
                return;
            if (doc.details.metadata == null)
                return;

            int size = linkGraph.size();
            int topology = invertedLinkGraph.numLinks(doc.url)
                         + externalDomainLinks.countForUrl(doc.url);

            doc.details.metadata = doc.details.metadata.withSizeAndTopology(size, topology);
        });

        siteWords.flagCommonSiteWords(ret);
        siteWords.flagAdjacentWords(topKeywords, invertedLinkGraph, ret);
    }

    private DomainIndexingState getState(String crawlerStatus) {
        return switch (CrawlerDomainStatus.valueOf(crawlerStatus)) {
            case OK -> DomainIndexingState.ACTIVE;
            case REDIRECT -> DomainIndexingState.REDIR;
            case BLOCKED -> DomainIndexingState.BLOCKED;
            default -> DomainIndexingState.ERROR;
        };
    }


}
