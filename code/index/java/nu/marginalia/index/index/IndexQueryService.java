package nu.marginalia.index.index;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import nu.marginalia.api.searchquery.model.query.SearchSubquery;
import nu.marginalia.array.buffer.LongQueryBuffer;
import nu.marginalia.index.model.QueryParams;
import nu.marginalia.index.model.SearchTerms;
import nu.marginalia.index.query.IndexQuery;
import nu.marginalia.index.query.IndexSearchBudget;
import nu.marginalia.index.results.model.ids.CombinedDocIdList;
import org.roaringbitmap.longlong.Roaring64Bitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.List;
import java.util.function.Consumer;

@Singleton
public class IndexQueryService {
    private final Marker queryMarker = MarkerFactory.getMarker("QUERY");

    private static final Logger logger = LoggerFactory.getLogger(IndexQueryService.class);
    private final StatefulIndex index;

    @Inject
    public IndexQueryService(StatefulIndex index) {
        this.index = index;
    }

    /** Execute subqueries and return a list of document ids.  The index is queried for each subquery,
     * at different priorty depths until timeout is reached or the results are all visited.
     * Then the results are combined.
     * */
    public void evaluateSubquery(SearchSubquery subquery,
                                 QueryParams queryParams,
                                 IndexSearchBudget timeout,
                                 Consumer<CombinedDocIdList> drain)
    {
        final SearchTerms searchTerms = new SearchTerms(subquery);
        final Roaring64Bitmap results = new Roaring64Bitmap();

        // These queries are different indices for one subquery
        List<IndexQuery> queries = index.createQueries(searchTerms, queryParams);
        for (var query : queries) {

            if (!timeout.hasTimeLeft())
                break;

            final LongQueryBuffer buffer = new LongQueryBuffer(512);

            while (query.hasMore() && timeout.hasTimeLeft())
            {
                buffer.reset();
                query.getMoreResults(buffer);

                for (int i = 0; i < buffer.size(); i++) {
                    results.add(buffer.data[i]);
                }

                if (results.getIntCardinality() > 512) {
                    drain.accept(new CombinedDocIdList(results));
                    results.clear();
                }
            }
        }

        if (!results.isEmpty()) {
            drain.accept(new CombinedDocIdList(results));
        }
    }

}
