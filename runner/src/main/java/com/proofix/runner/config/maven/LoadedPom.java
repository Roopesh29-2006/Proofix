package com.proofix.runner.config.maven;

import com.proofix.runner.config.ConfigContext;

/** A POM that was read and parsed, with the file it came from. */
public record LoadedPom(ConfigContext.Source src, Pom pom) {

    public String path() {
        return src.path();
    }

    /** Directory of this POM, "." for the root. */
    public String dir() {
        return src.file().dir();
    }
}
