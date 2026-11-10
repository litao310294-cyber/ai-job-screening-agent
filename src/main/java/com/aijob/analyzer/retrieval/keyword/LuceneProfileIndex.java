package com.aijob.analyzer.retrieval.keyword;

import com.aijob.analyzer.analysis.identity.ProfileArtifactIdentity;
import com.aijob.analyzer.retrieval.domain.ProfileChunk;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.similarities.BM25Similarity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

public class LuceneProfileIndex {

    private final LuceneDirectoryProvider directoryProvider;

    public LuceneProfileIndex(LuceneDirectoryProvider directoryProvider) {
        this.directoryProvider = directoryProvider;
    }

    public synchronized void index(ProfileArtifactIdentity identity, List<ProfileChunk> chunks) {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setSimilarity(new BM25Similarity());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

        try (IndexWriter writer = new IndexWriter(directoryProvider.getDirectory(), config)) {
            var scope = new org.apache.lucene.search.BooleanQuery.Builder()
                    .add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("userId", identity.userId())), org.apache.lucene.search.BooleanClause.Occur.MUST)
                    .add(LongField.newExactQuery("profileVersion", identity.profileVersion()), org.apache.lucene.search.BooleanClause.Occur.MUST).build();
            writer.deleteDocuments(scope);
            for (ProfileChunk chunk : chunks) {
                Document doc = new Document();
                doc.add(new StringField("chunkId", chunk.chunkId(), Field.Store.YES));
                doc.add(new StringField("factId", chunk.factId(), Field.Store.YES));
                doc.add(new StringField("userId", identity.userId(), Field.Store.YES));
                doc.add(new LongField("profileVersion", identity.profileVersion(), Field.Store.YES));
                doc.add(new TextField("content", chunk.content(), Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to index profile chunks", e);
        }
    }
}
