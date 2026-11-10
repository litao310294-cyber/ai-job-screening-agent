package com.aijob.analyzer.retrieval.keyword;

import org.apache.lucene.store.Directory;

import java.io.IOException;

public interface LuceneDirectoryProvider {
    Directory getDirectory() throws IOException;
}
