/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.util;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import org.h2.constant.ErrorCode;
import org.h2.constant.SysProperties;
import org.h2.message.DbException;
import org.h2.store.fs.FileUtils;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * This class allows to convert source code to a class. It uses one class loader
 * per class.
 */
public class SourceCompiler {

    static final JavaCompiler JAVA_COMPILER;
    
    private static final Class<?> JAVAC_SUN;
    
    private static final String COMPILE_DIR = Utils.getProperty("java.io.tmpdir", ".");
    
    /**
     * The class name to source code map.
     */
    final HashMap<String, String> sources = New.hashMap();

    /**
     * The class name to byte code map.
     */
    final HashMap<String, Class<?>> compiled = New.hashMap();
    
    boolean useJavaSystemCompiler = SysProperties.JAVA_SYSTEM_COMPILER;

    static {
        JavaCompiler c;
        try {
            c = ToolProvider.getSystemJavaCompiler();
        } catch (Exception e) {
            // ignore
            c = null;
        }
        JAVA_COMPILER = c;
        Class<?> clazz;
        try {
            clazz = Class.forName("com.sun.tools.javac.Main");
        } catch (Exception e) {
            clazz = null;
        }
        JAVAC_SUN = clazz;
    }

    /**
     * Set the source code for the specified class.
     * This will reset all compiled classes.
     *
     * @param className the class name
     * @param source the source code
     */
    public void setSource(String className, String source) {
        sources.put(className, source);
        compiled.clear();
    }
    
    /**
     * Enable or disable the usage of the Java system compiler.
     * 
     * @param enabled true to enable
     */
    public void setJavaSystemCompiler(boolean enabled) {
        this.useJavaSystemCompiler = enabled;
    }

    /**
     * Get the class object for the given name.
     *
     * @param packageAndClassName the class name
     * @return the class
     */
    public Class<?> getClass(String packageAndClassName) throws ClassNotFoundException {

        Class<?> compiledClass = compiled.get(packageAndClassName);
        if (compiledClass != null) {
            return compiledClass;
        }
        String source = sources.get(packageAndClassName);
        if (isGroovySource(source)) {
            Class<?> clazz = GroovyCompiler.parseClass(source, packageAndClassName);
            compiled.put(packageAndClassName, clazz);
            return clazz;
        }

        ClassLoader classLoader = new ClassLoader(getClass().getClassLoader()) {

            @Override
            public Class<?> findClass(String name) throws ClassNotFoundException {
                Class<?> classInstance = compiled.get(name);
                if (classInstance == null) {
                    String source = sources.get(name);
                    String packageName = null;
                    int idx = name.lastIndexOf('.');
                    String className;
                    if (idx >= 0) {
                        packageName = name.substring(0, idx);
                        className = name.substring(idx + 1);
                    } else {
                        className = name;
                    }
                    String s = getCompleteSourceCode(packageName, className, source);
                    if (JAVA_COMPILER != null && useJavaSystemCompiler) {
                        classInstance = javaxToolsJavac(packageName, className, s);
                    } else {
                        byte[] data = javacCompile(packageName, className, s);
                        if (data == null) {
                            classInstance = findSystemClass(name);
                        } else {
                            classInstance = defineClass(name, data, 0, data.length);
                        }
                    }
                    compiled.put(name, classInstance);
                }
                return classInstance;
            }
        };
        return classLoader.loadClass(packageAndClassName);
    }

    private static boolean isGroovySource(String source) {
        return source.startsWith("//groovy") || source.startsWith("@groovy");
    }

    /**
     * Get the first public static method of the given class.
     *
     * @param className the class name
     * @return the method name
     */
    public Method getMethod(String className) throws ClassNotFoundException {
        Class<?> clazz = getClass(className);
        Method[] methods = clazz.getDeclaredMethods();
        for (Method m : methods) {
            int modifiers = m.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)) {
                String name = m.getName();
                if (!name.startsWith("_") && !m.getName().equals("main")) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * Compile the given class. This method tries to use the class
     * "com.sun.tools.javac.Main" if available. If not, it tries to run "javac"
     * in a separate process.
     *
     * @param packageName the package name
     * @param className the class name
     * @param source the source code
     * @return the class file
     */
    byte[] javacCompile(String packageName, String className, String source) {
        File dir = new File(COMPILE_DIR);
        if (packageName != null) {
            dir = new File(dir, packageName.replace('.', '/'));
            FileUtils.createDirectories(dir.getAbsolutePath());
        }
        File javaFile = new File(dir, className + ".java");
        File classFile = new File(dir, className + ".class");
        try {
            OutputStream f = FileUtils.newOutputStream(javaFile.getAbsolutePath(), false);
            Writer out = IOUtils.getBufferedWriter(f);
            classFile.delete();
            out.write(source);
            out.close();
            if (JAVAC_SUN != null) {
                javacSun(javaFile);
            } else {
                javacProcess(javaFile);
            }
            byte[] data = new byte[(int) classFile.length()];
            DataInputStream in = new DataInputStream(new FileInputStream(classFile));
            in.readFully(data);
            in.close();
            return data;
        } catch (Exception e) {
            throw DbException.convert(e);
        } finally {
            javaFile.delete();
            classFile.delete();
        }
    }
    
    static String getCompleteSourceCode(String packageName, String className, String source) {
        if (source.startsWith("package ")) {
            return source;
        }
        StringBuilder buff = new StringBuilder();
        if (packageName != null) {
            buff.append("package ").append(packageName).append(";\n");
        }
        int endImport = source.indexOf("@CODE");
        String importCode = 
            "import java.util.*;\n" +
            "import java.math.*;\n" +
            "import java.sql.*;\n";
        if (endImport >= 0) {
            importCode = source.substring(0, endImport);
            source = source.substring("@CODE".length() + endImport);
        }
        buff.append(importCode);
        buff.append("public class ").append(className).append(
                " {\n" +
                "    public static ").append(source).append("\n" +
                "}\n");
        return buff.toString();
    }
    
    Class<?> javaxToolsJavac(String packageName, String className, String source) {
        String fullClassName = packageName + "." + className;
        StringWriter writer = new StringWriter();
        JavaFileManager fileManager = new
                ClassFileManager(JAVA_COMPILER
                    .getStandardFileManager(null, null, null));        
        ArrayList<JavaFileObject> compilationUnits = new ArrayList<JavaFileObject>();
        compilationUnits.add(new StringJavaFileObject(fullClassName, source));
        JAVA_COMPILER.getTask(writer, fileManager, null, null,
                null, compilationUnits).call();
        String err = writer.toString();
        throwSyntaxError(err);
        try {
            return fileManager.getClassLoader(null).loadClass(fullClassName);
        } catch (ClassNotFoundException e) {
            throw DbException.convert(e);
        }
    }

    private static void javacProcess(File javaFile) {
        exec("javac",
                "-sourcepath", COMPILE_DIR,
                "-d", COMPILE_DIR,
                "-encoding", "UTF-8",
                javaFile.getAbsolutePath());
    }

    private static int exec(String... args) {
        ByteArrayOutputStream buff = new ByteArrayOutputStream();
        try {
            ProcessBuilder builder = new ProcessBuilder();
            // The javac executable allows some of it's flags
            // to be smuggled in via environment variables.
            // But if it sees those flags, it will write out a message
            // to stderr, which messes up our parsing of the output.
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.command(args);

            Process p = builder.start();
            copyInThread(p.getInputStream(), buff);
            copyInThread(p.getErrorStream(), buff);
            p.waitFor();
            String err = new String(buff.toByteArray(), "UTF-8");
            throwSyntaxError(err);
            return p.exitValue();
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    private static void copyInThread(final InputStream in, final OutputStream out) {
        new Task() {
            @Override
            public void call() throws IOException {
                IOUtils.copy(in, out);
            }
        }.execute();
    }

    private static void javacSun(File javaFile) {
        PrintStream old = System.err;
        ByteArrayOutputStream buff = new ByteArrayOutputStream();
        PrintStream temp = new PrintStream(buff);
        try {
            System.setErr(temp);
            Method compile;
            compile = JAVAC_SUN.getMethod("compile", String[].class);
            Object javac = JAVAC_SUN.newInstance();
            compile.invoke(javac, (Object) new String[] {
                    "-sourcepath", COMPILE_DIR,
                    "-d", COMPILE_DIR,
                    "-encoding", "UTF-8",
                    javaFile.getAbsolutePath() });
            String err = new String(buff.toByteArray(), "UTF-8");
            throwSyntaxError(err);
        } catch (Exception e) {
            throw DbException.convert(e);
        } finally {
            System.setErr(old);
        }
    }

    private static void throwSyntaxError(String err) {
        if (err.startsWith("Note:")) {
            // unchecked or unsafe operations - just a warning
        } else if (err.length() > 0) {
            err = StringUtils.replaceAll(err, COMPILE_DIR, "");
            throw DbException.get(ErrorCode.SYNTAX_ERROR_1, err);
        }
    }


    /**
     * Access the Groovy compiler using reflection, so that we do not gain a
     * compile-time dependency unnecessarily.
     */
    private static final class GroovyCompiler {
        
        private static final Object LOADER;
        private static final Throwable INIT_FAIL_EXCEPTION;

        static {
            Object loader = null;
            Throwable initFailException = null;
            try {
                // Create an instance of ImportCustomizer
                Class<?> importCustomizerClass = Class.forName(
                        "org.codehaus.groovy.control.customizers.ImportCustomizer");
                Object importCustomizer = Utils.newInstance(
                        "org.codehaus.groovy.control.customizers.ImportCustomizer");
                // Call the method ImportCustomizer.addImports(String[])
                String[] importsArray = new String[] { 
                        "java.sql.Connection", 
                        "java.sql.Types", 
                        "java.sql.ResultSet",
                        "groovy.sql.Sql", 
                        "org.h2.tools.SimpleResultSet" 
                };
                Utils.callMethod(importCustomizer, "addImports", new Object[] { importsArray });

                // Call the method
                // CompilerConfiguration.addCompilationCustomizers(ImportCustomizer...)
                Object importCustomizerArray = Array.newInstance(importCustomizerClass, 1);
                Array.set(importCustomizerArray, 0, importCustomizer);
                Object configuration = Utils.newInstance(
                        "org.codehaus.groovy.control.CompilerConfiguration");
                Utils.callMethod(configuration, 
                        "addCompilationCustomizers", new Object[] { importCustomizerArray });

                ClassLoader parent = GroovyCompiler.class.getClassLoader();
                loader = Utils.newInstance(
                        "groovy.lang.GroovyClassLoader", parent, configuration);
            } catch (Exception ex) {
                initFailException = ex;
            }
            LOADER = loader;
            INIT_FAIL_EXCEPTION = initFailException;
        }

        public static Class<?> parseClass(String source, String packageAndClassName) {
            if (LOADER == null) {
                throw new RuntimeException("Compile fail: no Groovy jar in the classpath", INIT_FAIL_EXCEPTION);
            }
            try {
                Object codeSource = Utils.newInstance("groovy.lang.GroovyCodeSource", 
                        source, packageAndClassName + ".groovy", "UTF-8");
                Utils.callMethod(codeSource, "setCachable", false);
                Class<?> clazz = (Class<?>) Utils.callMethod(LOADER, "parseClass", codeSource);
                return clazz;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    /**
     * An in-memory java source file object.
     */
    static class StringJavaFileObject extends SimpleJavaFileObject {

        private final String sourceCode;

        public StringJavaFileObject(String className, String sourceCode) {
            super(URI.create("string:///" + className.replace('.', '/')
                + Kind.SOURCE.extension), Kind.SOURCE);
            this.sourceCode = sourceCode;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return sourceCode;
        }
        
    }
    
   /**
     * An in-memory java class object.
     */
    static class JavaClassObject extends SimpleJavaFileObject {

        protected final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public JavaClassObject(String name, Kind kind) {
            super(URI.create("string:///" + name.replace('.', '/')
                + kind.extension), kind);
        }

        public byte[] getBytes() {
            return out.toByteArray();
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            return out;
        }
    }
    
    /**
     * An in-memory class file manager.
     */
    static class ClassFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        JavaClassObject classObject;

        public ClassFileManager(StandardJavaFileManager standardManager) {
            super(standardManager);
        }

        @Override
        public ClassLoader getClassLoader(Location location) {
            return new SecureClassLoader() {
                @Override
                protected Class<?> findClass(String name)
                        throws ClassNotFoundException {
                    byte[] bytes = classObject.getBytes();
                    return super.defineClass(name, bytes, 0,
                            bytes.length);
                }
            };
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location,
                String className, Kind kind, FileObject sibling) throws IOException {
            classObject = new JavaClassObject(className, kind);
            return classObject;
        }
    }

}
