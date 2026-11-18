package com.proofix.runner.secrets;

import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.ScanResult;
import com.proofix.runner.scan.model.ScannedFile;
import com.proofix.runner.scan.model.Warning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Flags committed secret files by NAME ONLY. This class has no access to file contents and must
 * never open the files it reports.
 */
public final class SecretFiles {
    private SecretFiles() {}

    private static final Set<String> ALLOWED = Set.of(".env.example", ".env.sample", ".env.template");

    public static boolean isSecretName(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (ALLOWED.contains(n)) return false;
        return n.equals(".env") || n.startsWith(".env.");
    }

    public static List<Warning> find(ScanResult scan) {
        List<Warning> out = new ArrayList<>();
        for (ScannedFile f : scan.files()) {
            if (isSecretName(f.fileName())) {
                out.add(new Warning(WarningCodes.SECRET_FILE_PRESENT, f.path(),
                        "secret file present in the repository; contents were not read"));
            }
        }
        return out;
    }
}
