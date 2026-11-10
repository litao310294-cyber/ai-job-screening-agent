package com.aijob.analyzer.retrieval.keyword;

import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;

public class InMemoryLuceneDirectoryProvider implements LuceneDirectoryProvider {

    private final Directory directory = new ByteBuffersDirectory();

    @Override
    public Directory getDirectory() throws IOException {
        return directory;
    }
}
