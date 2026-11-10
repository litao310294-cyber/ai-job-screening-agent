package com.aijob.analyzer.retrieval.keyword;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.retrieval.KeywordRetriever;
import com.aijob.analyzer.retrieval.domain.RankedHit;
import com.aijob.analyzer.retrieval.domain.RetrievalQuery;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.LongField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

public class LuceneKeywordRetriever implements KeywordRetriever {

    private final LuceneDirectoryProvider directoryProvider;

    public LuceneKeywordRetriever(LuceneDirectoryProvider directoryProvider) {
        this.directoryProvider = directoryProvider;
    }

    @Override
    public List<RankedHit> search(ProfileArtifactIdentity profile, RetrievalQuery query, int limit) {
        try (DirectoryReader reader = DirectoryReader.open(directoryProvider.getDirectory())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());

            BooleanQuery.Builder finalQuery = new BooleanQuery.Builder();

            // Scope filter: userId and profileVersion
            finalQuery.add(new TermQuery(new Term("userId", profile.userId())), BooleanClause.Occur.MUST);
            finalQuery.add(LongField.newExactQuery("profileVersion", profile.profileVersion()), BooleanClause.Occur.MUST);

            // Content query
            QueryParser parser = new QueryParser("content", new StandardAnalyzer());
            Query contentQuery = parser.parse(QueryParser.escape(query.text()));
            finalQuery.add(contentQuery, BooleanClause.Occur.MUST);

            TopDocs topDocs = searcher.search(finalQuery.build(), limit);

            List<RankedHit> hits = new ArrayList<>();
            int rank = 1;
            var storedFields = searcher.storedFields();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                var doc = storedFields.document(scoreDoc.doc);
                hits.add(new RankedHit(
                        doc.get("chunkId"),
                        doc.get("factId"),
                        scoreDoc.score,
                        rank++
                ));
            }
            return hits;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to search lucene index", e);
        } catch (ParseException e) {
            throw new RuntimeException("Failed to parse query text: " + query.text(), e);
        }
    }
}
