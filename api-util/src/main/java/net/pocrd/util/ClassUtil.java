package net.pocrd.util;

import net.pocrd.define.ConstField;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 获取命名空间下的所有类
 *
 * @author rendong
 */
public class ClassUtil {
    public static Class<?>[] getAllClassesInPackage(String packageName) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            String path = packageName.replace('.', '/');
            Enumeration<URL> resources = classLoader.getResources(path);
            List<File> dirs = new LinkedList<File>();
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                dirs.add(new File(resource.getFile()));
            }
            List<Class<?>> classes = new LinkedList<Class<?>>();
            for (File directory : dirs) {
                classes.addAll(findClasses(directory, packageName));
            }
            return classes.toArray(new Class[classes.size()]);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Class<?>> findClasses(File directory, String packageName) throws ClassNotFoundException {
        List<Class<?>> classes = new LinkedList<Class<?>>();
        if (!directory.exists()) {
            return classes;
        }
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    classes.addAll(findClasses(file, packageName + "." + file.getName()));
                } else if (file.getName().endsWith(".class")) {
                    classes.add(loadClass(packageName + '.' + file.getName().substring(0, file.getName().length() - 6)));
                }
            }
        }
        return classes;
    }

    private static ClassLoader getTCL() {
        ClassLoader cl;
        if (System.getSecurityManager() == null) {
            cl = Thread.currentThread().getContextClassLoader();
        } else {
            cl = java.security.AccessController.doPrivileged(new java.security.PrivilegedAction<ClassLoader>() {
                @Override
                public ClassLoader run() {
                    return Thread.currentThread().getContextClassLoader();
                }
            });
        }

        return cl;
    }

    public static Class<?> loadClass(final String className) throws ClassNotFoundException {
        try {
            return getTCL().loadClass(className);
        } catch (final Throwable e) {
            return Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        }
    }

    public static ConcurrentHashMap<String, String> getAllProtoInPackage(String packageName) {
        try {
            ConcurrentHashMap<String, String> protos = new ConcurrentHashMap<String, String>();
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            String path = packageName.replace('.', '/');
            Enumeration<URL> resources = classLoader.getResources(path);
            List<File> dirs = new LinkedList<File>();
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                dirs.add(new File(resource.getFile()));
            }

            for (File directory : dirs) {
                protos.putAll(findProtoFiles(directory, packageName));
            }
            return protos;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static HashMap<String, String> findProtoFiles(File directory, String packageName) {
        HashMap<String, String> protos = new HashMap<String, String>();
        if (!directory.exists()) {
            return protos;
        }
        File[] files = directory.listFiles();
        try {
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        protos.putAll(findProtoFiles(file, packageName + "." + file.getName()));
                    } else if (file.getName().endsWith(".proto")) {
                        protos.put(file.getName().substring(0, file.getName().length() - 6), readAllContent(file));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("find proto file failed.", e);
        }
        return protos;
    }

    private static String readAllContent(File f) throws IOException {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] bs = new byte[fis.available()];
            int size = fis.read(bs);
            return new String(bs, 0, size, ConstField.UTF8);
        } finally {
            if (fis != null) {
                fis.close();
            }
        }
    }
}
