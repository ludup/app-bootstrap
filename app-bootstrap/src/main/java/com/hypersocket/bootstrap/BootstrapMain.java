package com.hypersocket.bootstrap;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

/**
 * Load an application from a defined set of properties.
 * 
 * @author lee
 * 
 */
public class BootstrapMain {

	Properties properties;
	BootstrapClassLoader uberClassLoader;
	String applicationProperties = "application.properties";
	File javaLibraryPath;

	static BootstrapMain instance;

	public static void main(String[] args) throws Exception {

		try {
			instance = new BootstrapMain();
			instance.runApplication(args);
		} catch (Throwable t) {
			System.err.println("Failed to start");
			t.printStackTrace();
		}
	}

	public static BootstrapMain getInstance() {
		return instance;
	}

	private String[] processArguments(String[] args) {

		List<String> cmdargs = new ArrayList<String>();
		for (String arg : args) {
			if (arg.startsWith("log4j=")) {
				/**
				 * We removed support for this in 2.2
				 */
				continue;
			}
			if (arg.startsWith("props=")) {
				applicationProperties = arg.substring(6);
				continue;
			}
			cmdargs.add(arg);
		}
		
		return cmdargs.toArray(new String[0]);
	}

	public static Date getReleaseDate() {
		return new Date(/* RELEASE_DATE */);
	}

	public void runApplication(String[] args) throws IOException {

		args = processArguments(args);

		start(args);

	}

	static void log(String message) {
		if(isLogEnabled()) {
			System.err.println(message);
		}
	}
	
	static void log(String message, Throwable t) {
		if(isLogEnabled()) {
			System.err.println(message);
			t.printStackTrace();
		}
	}
	
	static boolean isLogEnabled() {
		return Boolean.getBoolean("bootstrap.debug");
	}
	
	public void start(String[] args) throws IOException {

		properties = new Properties();
		FileInputStream in = new FileInputStream("conf" + File.separator + applicationProperties);

		try {
			properties.load(in);
		} finally {
			in.close();
		}
		
		if (!properties.containsKey("app.id")) {
			throw new IOException(applicationProperties + " must have an app.id property");
		}

		if (!properties.containsKey("app.main")) {
			throw new IOException(applicationProperties + " must have an app.main property");
		}

		if (!properties.containsKey("app.name")) {
			throw new IOException(applicationProperties + " must have an app.name property");
		}
		
		if (properties.containsKey("app.repos")) {
			System.setProperty("hypersocket.repos", properties.getProperty("app.repos"));
		}

		System.setProperty("hypersocket.id", properties.getProperty("app.id"));
		System.setProperty("hypersocket.productName", properties.getProperty("app.name"));
		

		/**
		 * Set any number of arbitary system properties
		 */
		for(String key : properties.stringPropertyNames()) {
			if(key.startsWith("app.")) {
				continue;
			}
			System.setProperty(key, properties.getProperty(key));
		}
		
		log(System.getProperty("sun.java.command"));

		if (System.getProperty("os.name").toLowerCase().startsWith("linux")) {
			log("Display: " + System.getenv("DISPLAY"));
		}

		for (String arg : args) {
			log(arg);
		}
		
		
		for(File file : new File("conf").listFiles(new FileFilter() {
			@Override
			public boolean accept(File pathname) {
				return pathname.getName().startsWith("runOnce.") || pathname.getName().startsWith("runOnceAsAdmin.");
			}
		})) {
			ProcessBuilder shellBuilder = new ProcessBuilder();
			shellBuilder.redirectErrorStream(true);
			if(file.getName().startsWith("runOnceAsAdmin.") && !System.getProperty("user.name").equals("root")) {
				log("Elevating boot script " + file + " to administrator privileges");
				if(!file.canExecute()) {
					file.setExecutable(true, false);
				}
				shellBuilder.command().addAll(Arrays.asList(bashSilentSudoCommand(System.getProperty("sudo.password", "").toCharArray(), file.getAbsolutePath())));
			}
			else {
				shellBuilder.command().add(file.getAbsolutePath());
			}
			Process process = shellBuilder.start();
			try {
				log("Executing boot script " + file);
				IOUtils.copy(process.getInputStream(), System.out);
				if(process.waitFor() != 0)
					throw new IOException(String.format("Failed to execute boot script %s, returned exit code %d.", file, process.exitValue()));
				log("Executed boot script " + file);
				file.delete();
			}
			catch(InterruptedException ioe) {
				throw new IOException("Interrupted while executing boot script.", ioe);
			}
		}

		File tmp = new File(
				properties.getProperty("app.tmp", "tmp").replace("${user.home}", System.getProperty("user.home")));
		try {
			FileUtils.deleteDirectory(tmp);
		} catch (Exception e) {
			log(String.format(
					"Could not delete temporary directory %s. This may be due to the file being in use, "
					+ "and you may experience unexpected behavior until this is resolved. Trying closing "
					+ "down the client and manually removing all files from the temporary directory.",
					tmp));
		}

		javaLibraryPath = new File(tmp, "lib");
		javaLibraryPath.mkdirs();
		
		loadApplication(properties.getProperty("app.name"), properties.getProperty("app.archive"),
				properties.getProperty("app.dist", "dist"), tmp,
				properties.getProperty("app.additionalClasspath"));

		ClassLoader applicationClassLoader = uberClassLoader;

		if (Boolean.getBoolean("hypersocket.development") && !Boolean.getBoolean("hypersocket.development.useUberClassloader")) {
			applicationClassLoader = ClassLoader.getSystemClassLoader();
		}
		Thread.currentThread().setContextClassLoader(applicationClassLoader);

		if (isLogEnabled()) {
			log("Starting application");
		}
		
		try {
			Class<?> clz = Class.forName(properties.getProperty("app.main"), true, applicationClassLoader);

			try {
				Method m = clz.getMethod("runApplication", Runnable.class, Runnable.class, String[].class);

				m.invoke(null, new Runnable() {
					public void run() {
						restart();
					}
				}, new Runnable() {
					public void run() {
						shutdown();
					}
				}, args);
			} catch (Exception e) {
				
				log("App does not support String[] argument");
				
				try {
					Method m = clz.getMethod("runApplication", Runnable.class, Runnable.class);
	
					m.invoke(null, new Runnable() {
						public void run() {
							restart();
						}
					}, new Runnable() {
						public void run() {
							shutdown();
						}
					});
				
				} catch(Exception e2) {
					
					log("App does not support runApplication method; reverting to main");
					
					Method m = clz.getMethod("main", String[].class);
					
					m.invoke(null, (Object)args);
				}
 			}

			if (isLogEnabled()) {
				log("Application started");
			}

		} catch (Throwable e) {
			log("Could not load and run main class " + properties.getProperty("app.main"), e);
		}

	}

	/**
	 * Restart the current Java application
	 * 
	 * @param runBeforeRestart
	 *            some custom code to be run before restarting
	 * @throws IOException
	 */
	public void restart() {
		System.exit(99);
	}

	public void shutdown() {
		System.exit(0);
	}

	protected void loadApplication(String appName, 
			String appArchive, 
			String distName, 
			File tmpDir,
			String additionalClasspath) throws IOException {

		if (isLogEnabled()) {
			log("Loading application " + appName);
		}

		List<URL> jarURLs = new ArrayList<URL>();
		List<String> artifactIds = new ArrayList<String>();

		File distFolder = new File(distName);
		File extFolder = new File("ext");
		
		System.setProperty("hypersocket.bootstrap.archivesDir", distFolder.getAbsolutePath());
		System.setProperty("hypersocket.bootstrap.distDir", tmpDir.getAbsolutePath());
		System.setProperty("hypersocket.bootstrap.systemArchive", appArchive);

		if (isLogEnabled()) {
			log("Looking for archives in " + distFolder.getAbsolutePath());
		}

		if (!distFolder.exists()) {
			if (!Boolean.getBoolean("hypersocket.development")) {
				String[] ls = distFolder.list();
				if (ls == null || ls.length == 0) {
					throw new IOException("The dist folder for " + appName + " appears to be empty!");
				}
			}
		}

		File[] extensions = distFolder.listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.getName().endsWith(".zip");
			}

		});

		File[] additionalLibs = extFolder.listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.getName().endsWith(".jar");
			}

		});
		Set<String> jarNames = new HashSet<String>();
		
		if(additionalLibs!=null) {
			for(File ext : additionalLibs) {
				jarURLs.add(ext.toURI().toURL());
				jarNames.add(ext.getName());
			}
		}
		
		if(additionalClasspath!=null) {
			String[] files = additionalClasspath.split(",");
			for(String file : files) {
				File f = new File(file);
				if(f.exists()) {
					jarURLs.add(f.toURI().toURL());
					jarNames.add(f.getName());
				}
			}
		}
		
		if (extensions != null) {
			
			for (File ext : extensions) {
				String name = ext.getName().substring(0, ext.getName().length() - 4);
				artifactIds.add(name);
				List<URL> jars = loadExtensionJars(name, ext, tmpDir);
				for (URL jar : jars) {
					String jarName = jar.getPath();
					if (jarName.indexOf('/') > -1) {
						jarName = jarName.substring(jarName.lastIndexOf('/'));
					}
					if (jarNames.contains(jarName)) {
						if (isLogEnabled()) {
							log(jarName + " has already been included by another extension");
						}
						continue;
					}
					jarURLs.add(jar);
					jarNames.add(jarName);
				}
			}
		}

		uberClassLoader = new BootstrapClassLoader(jarURLs.toArray(new URL[0]),
				ClassLoader.getSystemClassLoader().getParent());

		for (URL u : uberClassLoader.getURLs()) {
			log("Classpath: " + u.toExternalForm());
		}

		if (isLogEnabled()) {
			log("Loaded application " + appName);
		}

	}

	protected List<URL> loadExtensionJars(String name, File extensionZip, File tmpDir) throws IOException {

		if (isLogEnabled()) {
			log("Loading application archive " + extensionZip.getName());
		}

		try {
			File extensionFolder = unzip(extensionZip, tmpDir);

			String bootstrapArchives = System.getProperty("hypersocket.bootstrap.archives", "");
			System.setProperty("hypersocket.bootstrap.archives",
					(bootstrapArchives.equals("") ? bootstrapArchives : bootstrapArchives + ";")
							+ extensionFolder.getName() + "=" + extensionZip.getAbsolutePath());

			List<URL> jarURLs = new ArrayList<URL>();

			File[] jars = extensionFolder.listFiles(new FileFilter() {
				@Override
				public boolean accept(File file) {
					return file.getName().endsWith(".jar");
				}
			});

			if (jars != null) {
				for (File jar : jars) {
					if (isLogEnabled()) {
						log("Loading application jar " + jar.toURI().toURL());
					}
					jarURLs.add(jar.toURI().toURL());
				}
			}

			String platform = "unknown";
			if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
				platform = "win" + File.separator + System.getProperty("os.arch");
			} else if (System.getProperty("os.name").toLowerCase().startsWith("mac")) {
				platform = "osx" + File.separator + System.getProperty("os.arch");
			} else if (System.getProperty("os.name").toLowerCase().startsWith("linux")) {
				platform = "linux" + File.separator + System.getProperty("os.arch");
			}

			if (isLogEnabled()) {
				log("Looking for platform jars " + platform);
			}

			File platformFolder = new File(extensionFolder, platform);
			if (platformFolder.exists()) {
				jars = platformFolder.listFiles(new FileFilter() {
					@Override
					public boolean accept(File file) {
						return file.getName().endsWith(".jar");
					}
				});

				if (jars != null) {
					for (File jar : jars) {
						if (isLogEnabled()) {
							log("Loading platform jar " + jar.toURI().toURL());
						}
						jarURLs.add(jar.toURI().toURL());
					}
				}
			}

			if (isLogEnabled()) {
				log("Looking for jni libs");
			}

			File jniFolder = new File(extensionFolder, "bin" + File.separator + "lib");
			if (jniFolder.exists()) {
				File[] libs = jniFolder.listFiles();

				if (libs != null) {
					for (File lib : libs) {
						if (isLogEnabled()) {
							log("Copying jni lib " + lib.toURI().toURL());
						}
						FileUtils.copyFile(lib, new File(javaLibraryPath, lib.getName()));
					}
				}
			}

			return jarURLs;

		} catch (IOException ex) {
			log("Failed to extract extension " + name, ex);
			return new ArrayList<URL>();
		}
	}

	public static File unzip(File zipFile, File outputFolder) throws IOException {

		byte[] buffer = new byte[1024];
		ZipInputStream zis = null;
		FileInputStream in = new FileInputStream(zipFile);
		try {

			if (!outputFolder.exists()) {
				outputFolder.mkdir();
			}

			zis = new ZipInputStream(in);

			ZipEntry ze = zis.getNextEntry();

			File rootDir = null;
			while (ze != null) {

				String fileName = ze.getName();

				if (ze.isDirectory()) {

					File newFile = new File(outputFolder, fileName);
					newFile.mkdirs();

					if (rootDir == null) {
						rootDir = newFile;
					}
				} else {
					File newFile = new File(outputFolder, fileName);

					newFile.getParentFile().mkdirs();

					try {
						FileOutputStream fos = new FileOutputStream(newFile);
	
						try {
							int len;
							while ((len = zis.read(buffer)) > 0) {
								fos.write(buffer, 0, len);
							}
						} finally {
							fos.close();
							if (ze.getTime() > -1) {
								newFile.setLastModified(ze.getTime());
							}
						}
					}
					catch(IOException ioe) {
						log(String.format("Failed to extract to file %s, is it in use?", newFile), ioe);
					}

				}
				ze = zis.getNextEntry();
			}

			zis.closeEntry();
			zis.close();

			return rootDir;
		} finally {
			try {
				zis.close();
			} catch (Exception e) {
			}
			try {
				in.close();
			} catch (IOException e) {
			}
		}
	}
	
	private static String[] bashSilentSudoCommand(char[] password, String cmd, String... cmdline) {
		return new String[] { "/bin/bash", "-c", createSudoCommand(password, cmd, cmdline) };	
	}
	
	private static String createSudoCommand(char[] password, String cmd, String[] cmdline) {
		
		StringBuffer buf = new StringBuffer();
		buf.append("echo ");
		buf.append(password);
		buf.append("|");
		buf.append("sudo -S");
		buf.append(" ");
		if(cmd.contains(" ")) {
			buf.append('\'');
			buf.append(cmd);
			buf.append('\'');
		} else {
			buf.append(cmd);
		}
		for(String s : cmdline) {
			buf.append(" ");
			if(s.contains(" ")) {
				buf.append('\'');
				buf.append(s);
				buf.append('\'');
			} else {
				buf.append(s);
			}
			
		}
		return buf.toString();
	}
}
