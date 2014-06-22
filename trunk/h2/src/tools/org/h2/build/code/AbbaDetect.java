/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.build.code;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Enable / disable AB-BA deadlock detector code.
 */
public class AbbaDetect {

    /**
     * This method is called when executing this application from the command
     * line.
     *
     * @param args the command line parameters
     */
    public static void main(String... args) throws Exception {
        String baseDir = "src/main";
        process(new File(baseDir), true);
    }

    private static void process(File file, boolean enable) throws IOException {
        String name = file.getName();
        if (file.isDirectory()) {
            if (name.equals("CVS") || name.equals(".svn")) {
                return;
            }
            for (File f : file.listFiles()) {
                process(f, enable);
            }
            return;
        }
        if (!name.endsWith(".java")) {
            return;
        }
        if (name.endsWith("AbbaDetector.java")) {
            return;
        }
        RandomAccessFile in = new RandomAccessFile(file, "r");
        byte[] data = new byte[(int) file.length()];
        in.readFully(data);
        in.close();
        String source = new String(data, "UTF-8");
        String original = source;

        source = disable(source);
        if (enable) {
            String s2 = enable(source);
            if (!source.equals(disable(s2))) {
                throw new IOException("Could not revert changes for file " + file);
            }
            source = s2;
        }

        if (source.equals(original)) {
            return;
        }
        File newFile = new File(file + ".new");
        RandomAccessFile out = new RandomAccessFile(newFile, "rw");
        out.write(source.getBytes("UTF-8"));
        out.close();

        File oldFile = new File(file + ".old");
        file.renameTo(oldFile);
        newFile.renameTo(file);
        oldFile.delete();
    }

    private static String disable(String source) {
        source = source.replaceAll("\\{org.h2.util.AbbaDetector.begin\\(.*\\);", "{");
        source = source.replaceAll("org.h2.util.AbbaDetector.begin\\((.*\\(\\))\\)", "$1");
        source = source.replaceAll("org.h2.util.AbbaDetector.begin\\((.*)\\)", "$1");
        source = source.replaceAll("synchronized  ", "synchronized ");
        return source;
    }

    private static String enable(String source) {
        // the word synchronized within single line comments comments
        source = source.replaceAll("(// .* synchronized )([^ ])", "$1 $2");

        source = source.replaceAll("synchronized \\((.*)\\(\\)\\)",
                "synchronized  \\(org.h2.util.AbbaDetector.begin\\($1\\(\\)\\)\\)");
        source = source.replaceAll("synchronized \\((.*)\\)",
                "synchronized  \\(org.h2.util.AbbaDetector.begin\\($1\\)\\)");

        source = source.replaceAll("static synchronized ([^ (].*)\\{",
                "static synchronized  $1{org.h2.util.AbbaDetector.begin\\(null\\);");
        source = source.replaceAll("synchronized ([^ (].*)\\{",
                "synchronized  $1{org.h2.util.AbbaDetector.begin\\(this\\);");

        return source;
    }

}
