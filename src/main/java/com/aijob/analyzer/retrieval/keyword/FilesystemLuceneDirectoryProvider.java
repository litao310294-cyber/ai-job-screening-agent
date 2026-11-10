package com.aijob.analyzer.retrieval.keyword;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class FilesystemLuceneDirectoryProvider implements LuceneDirectoryProvider {

    private final Path rootPath;

    public FilesystemLuceneDirectoryProvider(@Value("${app.retrieval.lucene-root:./data/lucene}") String luceneRoot) {
        this.rootPath = Paths.get(luceneRoot);
    }

    @Override
    public Directory getDirectory() throws IOException {
        if (!Files.exists(rootPath)) {
            Files.createDirectories(rootPath);
        }
        return FSDirectory.open(rootPath);
    }
}
