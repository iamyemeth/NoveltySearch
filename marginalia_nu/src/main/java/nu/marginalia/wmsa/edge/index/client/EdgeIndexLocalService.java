package nu.marginalia.wmsa.edge.index.client;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import nu.marginalia.util.ListChunker;
import nu.marginalia.util.dict.DictionaryHashMap;
import nu.marginalia.wmsa.configuration.server.Context;
import nu.marginalia.wmsa.edge.converting.interpreter.instruction.DocumentKeywords;
import nu.marginalia.wmsa.edge.index.journal.SearchIndexJournalWriterImpl;
import nu.marginalia.wmsa.edge.index.journal.model.SearchIndexJournalEntry;
import nu.marginalia.wmsa.edge.index.journal.model.SearchIndexJournalEntryHeader;
import nu.marginalia.wmsa.edge.index.lexicon.KeywordLexicon;
import nu.marginalia.wmsa.edge.index.lexicon.journal.KeywordLexiconJournal;
import nu.marginalia.wmsa.edge.model.EdgeDomain;
import nu.marginalia.wmsa.edge.model.EdgeUrl;
import nu.marginalia.wmsa.edge.model.id.EdgeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

@Singleton
public class EdgeIndexLocalService implements EdgeIndexWriterClient {

    private final KeywordLexicon lexicon;
    private final SearchIndexJournalWriterImpl indexWriter;
    private static final Logger logger = LoggerFactory.getLogger(EdgeIndexLocalService.class);

    @Inject
    public EdgeIndexLocalService(@Named("local-index-path") Path path) throws IOException {
        long hashMapSize = 1L << 31;

        if (Boolean.getBoolean("small-ram")) {
            hashMapSize = 1L << 27;
        }

        var lexiconJournal = new KeywordLexiconJournal(path.resolve("dictionary.dat").toFile());
        lexicon = new KeywordLexicon(lexiconJournal, new DictionaryHashMap(hashMapSize));
        indexWriter = new SearchIndexJournalWriterImpl(lexicon, path.resolve("index.dat").toFile());
    }

    public void putWords(Context ctx, EdgeId<EdgeDomain> domain, EdgeId<EdgeUrl> url,
                         DocumentKeywords wordSet, int writer) {
        if (wordSet.keywords().length == 0)
            return;

        if (domain.id() <= 0 || url.id() <= 0) {
            logger.warn("Bad ID: {}:{}", domain, url);
            return;
        }

        for (var chunk : ListChunker.chopList(wordSet, SearchIndexJournalEntry.MAX_LENGTH)) {

            var entry = new SearchIndexJournalEntry(getOrInsertWordIds(chunk.keywords(), chunk.metadata()));
            var header = new SearchIndexJournalEntryHeader(domain, url, wordSet.block());

            indexWriter.put(header, entry);
        }

    }

    private long[] getOrInsertWordIds(String[] words, long[] meta) {
        long[] ids = new long[words.length*2];
        int putIdx = 0;

        for (int i = 0; i < words.length; i++) {
            String word = words[i];

            long id = lexicon.getOrInsert(word);
            if (id != DictionaryHashMap.NO_VALUE) {
                ids[putIdx++] = id;
                ids[putIdx++] = meta[i];
            }
        }

        if (putIdx != words.length*2) {
            ids = Arrays.copyOf(ids, putIdx);
        }
        return ids;
    }

    @Override
    public void close() throws Exception {
        indexWriter.close();
        lexicon.close();
    }
}
