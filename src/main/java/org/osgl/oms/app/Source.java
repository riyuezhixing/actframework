package org.osgl.oms.app;

import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.osgl._;
import org.osgl.oms.util.Names;
import org.osgl.util.E;
import org.osgl.util.FastStr;
import org.osgl.util.IO;
import org.osgl.util.S;

import java.io.File;
import java.util.StringTokenizer;

/**
 * Encapsulate java source unit data including source code, byte code etc.
 * A java source unit specifies a java class
 */
public class Source {
    public static enum State {
        /**
         * File deleted
         */
        DELETED,

        /**
         * Source code loaded
         */
        LOADED,

        /**
         * Byte code compiled out of the source code
         */
        COMPILED,

        /**
         * Tried to compile but there is compile error
         */
        ERROR_COMPILE,

        /**
         * Byte code enhanced by framework
         */
        ENHANCED
    }

    // the source file
    private File file;

    // the class name. Can't be 1-1 map to file as
    // embedded classes do not have separate source file
    private String simpleName;

    private String packageName;

    // The source code
    private String code;

    // The byte code
    private byte[] bytes;

    private State state;

    private long ts;

    private Source(File file, String className) {
        E.NPE(file, className);
        this.file = file;
        this.simpleName = S.afterLast(className, ".");
        this.packageName = S.beforeLast(className, ".");
    }

    public String simpleName() {
        return simpleName;
    }

    public String packageName() {
        return packageName;
    }

    public String className() {
        StringBuilder sb = S.builder(packageName).append(".").append(simpleName);
        return sb.toString();
    }

    public String code() {
        if (null == code) {
            load();
        }
        return code;
    }

    public byte[] bytes() {
        return bytes;
    }

    public File file() {
        return file;
    }

    public void load() {
        code = IO.readContentAsString(file);
        updateState(State.LOADED);
    }

    void compiled(byte[] bytecode) {
        this.bytes = _.notNull(bytecode);
        updateState(State.COMPILED);
    }

    public void refresh() {
        bytes = null;
        ts = 0L;
        tryLoadSourceFile();
    }

    private void updateState(State state) {
        this.state = state;
        this.ts = _.ms();
    }

    private void tryLoadSourceFile() {
        if (file.exists()) {
            code = IO.readContentAsString(file);
            updateState(State.LOADED);
        } else {
            updateState(State.DELETED);
        }
    }

    public static Source ofClass(File sourceRoot, String className) {
        File file = Util.sourceFile(sourceRoot, className);
        if (file.exists() && file.canRead()) {
            return new Source(file, className);
        }
        return null;
    }

    private ICompilationUnit compilationUnit = new ICompilationUnit() {

        char[] mainTypeName = _mainTypeName();
        char[][] packageName = _packageName();
        char[] fileName = _fileName();

        @Override
        public char[] getContents() {
            return code().toCharArray();
        }

        @Override
        public char[] getMainTypeName() {
            return mainTypeName;
        }

        private char[] _mainTypeName() {
            String s = simpleName();
            int pos = s.indexOf('$');
            if (pos > -1) {
                s = s.substring(0, pos);
            }
            return s.toCharArray();
        }

        @Override
        public char[][] getPackageName() {
            return packageName;
        }

        char[][] _packageName() {
            StringTokenizer tokens = new StringTokenizer(packageName(), ".");
            char[][] ca = new char[tokens.countTokens() - 1][];
            for (int i = 0; i < ca.length; i++) {
                ca[i] = tokens.nextToken().toCharArray();
            }
            return ca;
        };

        @Override
        public boolean ignoreOptionalProblems() {
            return false;
        }

        @Override
        public char[] getFileName() {
            return fileName;
        }

        char[] _fileName() {
            String s = simpleName();
            int pos = s.indexOf('$');
            if (pos > -1) {
                s = s.substring(0, pos);
            }
            s = s.replace('.', '/');
            s = s + ".java";
            return s.toCharArray();
        }
    };

    ICompilationUnit compilationUnit() {
        return compilationUnit;
    }

    public static enum Util {
        ;
        public static String className(File sourceRoot, File file) {
            return Names.fileToClass(sourceRoot, file.getAbsolutePath());
        }

        public static File sourceFile(File sourceRoot, String className) {
            FastStr s = FastStr.of(className).beforeFirst('$');
            s = s.replace('.', File.separatorChar).append(".java");
            return new File(sourceRoot, s.toString());
        }

        public static void main(String[] args) throws Exception {
        }
    }
}
