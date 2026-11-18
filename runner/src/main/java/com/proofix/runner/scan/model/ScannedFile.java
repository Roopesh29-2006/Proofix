package com.proofix.runner.scan.model;

/** A regular file inside the root. {@code path} is relative and uses '/' separators. */
public record ScannedFile(String path, long size) {

    public String fileName() {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** Directory part of the path, "." for files in the root. */
    public String dir() {
        int i = path.lastIndexOf('/');
        return i < 0 ? "." : path.substring(0, i);
    }
}
