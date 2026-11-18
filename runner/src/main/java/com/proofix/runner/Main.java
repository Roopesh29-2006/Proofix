package com.proofix.runner;

import com.proofix.runner.cli.Cli;

/** Entry point: {@code java -jar devbuddy-runner.jar scan <repoPath> [--out facts.json]}. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(new Cli().run(args, System.out, System.err));
    }
}
