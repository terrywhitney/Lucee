/**
 * Copyright (c) 2014, the Railo Company Ltd.
 * Copyright (c) 2015, Lucee Assosication Switzerland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package lucee.loader.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.felix.framework.Felix;
import org.apache.felix.framework.Logger;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.intergral.fusiondebug.server.FDControllerFactory;

import lucee.VersionInfo;
import lucee.commons.io.log.Log;
import lucee.commons.lang.ConcurrentHashMapAsHashtable;
import lucee.loader.TP;
import lucee.loader.engine.mvn.MavenUpdateProvider;
import lucee.loader.osgi.BundleCollection;
import lucee.loader.osgi.BundleLoader;
import lucee.loader.osgi.BundleUtil;
import lucee.loader.osgi.LoggerImpl;
import lucee.loader.util.ExtensionFilter;
import lucee.loader.util.Util;
import lucee.loader.util.ZipUtil;
import lucee.runtime.config.ConfigServer;
import lucee.runtime.config.Identification;
import lucee.runtime.config.Password;
import lucee.runtime.util.Pack200Util;

/**
 * Factory to load CFML Engine
 */
public class CFMLEngineFactory extends CFMLEngineFactorySupport {

	/** Project sources root dir, e.g. /Users/mic/Projects/Lucee/Lucee6 */
	public static final String ARG_PROJECT_DIR = "LUCEE_PROJECT_DIR";

	/** Compiler output root dir, e.g. /Users/mic/Projects/Lucee/ide-compiler-output-6 */
	public static final String ARG_CLASSES_DIR = "LUCEE_CLASSES_DIR";

	// set to false to disable patch loading, for example in major alpha releases
	private static final boolean PATCH_ENABLED = true;
	public static final Version VERSION_ZERO = new Version(0, 0, 0, "0");
	private static final String UPDATE_LOCATION = "https://update.lucee.org"; // MUST from server.xml
	private static final long GB1 = 1024 * 1024 * 1024;
	private static final long MB100 = 1024 * 1024 * 100;
	private static final int MAX_REDIRECTS = 5;
	private static final Version MIN_VERSION = toVersion("5.0.0.248", null);

	private static CFMLEngineFactory factory;
	// private static CFMLEngineWrapper engineListener;
	private static CFMLEngineWrapper singelton;

	private static File luceeServerRoot;

	private Felix felix;
	private BundleCollection bundleCollection;
	// private CFMLEngineWrapper engine;

	private final ClassLoader mainClassLoader = new TP().getClass().getClassLoader();
	private Version version;
	private final List<EngineChangeListener> listeners = new ArrayList<EngineChangeListener>();
	private File resourceRoot;

	// private PrintWriter out;

	private final LoggerImpl logger;

	// do not remove/ranme, grapped by core directly
	protected ServletConfig config;

	private boolean embedded;

	protected CFMLEngineFactory(final ServletConfig config) {
		System.setProperty("org.apache.commons.logging.LogFactory.HashtableImpl", ConcurrentHashMapAsHashtable.class.getName());
		File logFile = null;
		this.config = config;
		try {
			logFile = new File(getResourceRoot(), "context/logs/felix.log");
			if (logFile.isFile()) {
				// more than a GB (from the time we did not control it)
				if (logFile.length() > GB1) {
					logFile.delete(); // we simply delete it
				}
				else if (logFile.length() > MB100) {
					File bak = new File(logFile.getParentFile(), "felix.1.log");
					if (bak.isFile()) bak.delete();
					logFile.renameTo(bak);
				}

			}

		}
		catch (final IOException e) {
			e.printStackTrace();
		}
		logFile.getParentFile().mkdirs();
		logger = new LoggerImpl(logFile);
	}

	/**
	 * returns instance of this factory (singelton = always the same instance) do auto update when
	 * changes occur
	 *
	 * @param config servlet config
	 * @return Singelton Instance of the Factory
	 * @throws ServletException servlet exception
	 */
	public synchronized static CFMLEngine getInstance(final ServletConfig config) throws ServletException {

		if (singelton != null) {
			if (factory == null) factory = singelton.getCFMLEngineFactory(); // not sure if this ever is done, but it does not hurt
			return singelton;
		}

		if (factory == null) factory = new CFMLEngineFactory(config);

		// read init param from config
		factory.readInitParam(config);

		factory.initEngineIfNecessary();
		singelton.addServletConfig(config);

		// add listener for update
		// factory.addListener(singelton);
		return singelton;
	}

	/**
	 * returns instance of this factory (singelton = always the same instance) do auto update when
	 * changes occur
	 *
	 * @return Singelton Instance of the Factory
	 * @throws RuntimeException runtime exception
	 */
	public static CFMLEngine getInstance() throws RuntimeException {
		if (singelton != null) return singelton;
		throw new RuntimeException("Engine is not initialized, you must first call getInstance(ServletConfig)");
	}

	public static void registerInstance(final CFMLEngine engine) {
		if (engine instanceof CFMLEngineWrapper) throw new RuntimeException("That should not happen!");
		setEngine(engine);
	}

	/**
	 * returns instance of this factory (singelton always the same instance)
	 *
	 * @param config servlet config
	 * @param listener listener
	 * @return Singelton Instance of the Factory
	 * @throws ServletException servlet exception
	 */
	public static CFMLEngine getInstance(final ServletConfig config, final EngineChangeListener listener) throws ServletException {
		getInstance(config);

		// add listener for update
		factory.addListener(listener);

		// read init param from config
		factory.readInitParam(config);

		factory.initEngineIfNecessary();
		singelton.addServletConfig(config);

		// make the FDController visible for the FDClient
		FDControllerFactory.makeVisible();

		return singelton;
	}

	void readInitParam(final ServletConfig config) {
		if (luceeServerRoot != null) return;

		String strEmbedded = config.getInitParameter("embedded");
		embedded = false;
		if (!Util.isEmpty(strEmbedded, true)) {
			strEmbedded.trim().toLowerCase();
			embedded = strEmbedded.equals("true") || strEmbedded.equals("yes");
		}

		String initParam = config.getInitParameter("lucee-server-directory");
		if (Util.isEmpty(initParam)) initParam = config.getInitParameter("lucee-server-root");
		if (Util.isEmpty(initParam)) initParam = config.getInitParameter("lucee-server-dir");
		if (Util.isEmpty(initParam)) initParam = config.getInitParameter("lucee-server");
		if (Util.isEmpty(initParam)) initParam = Util._getSystemPropOrEnvVar("lucee.server.dir", null);

		initParam = parsePlaceHolder(removeQuotes(initParam, true));
		try {
			if (!Util.isEmpty(initParam)) {
				final File root = new File(initParam);
				if (!root.exists()) {
					if (root.mkdirs()) {
						luceeServerRoot = root.getCanonicalFile();
						return;
					}
				}
				else if (root.canWrite()) {
					luceeServerRoot = root.getCanonicalFile();
					return;
				}
			}
		}
		catch (final IOException ioe) {
			ioe.printStackTrace();
		}
	}

	/**
	 * adds a listener to the factory that will be informed when a new engine will be loaded.
	 *
	 * @param listener
	 */
	private void addListener(final EngineChangeListener listener) {
		if (!listeners.contains(listener)) listeners.add(listener);
	}

	/**
	 * @throws ServletException
	 */
	private void initEngineIfNecessary() throws ServletException {
		if (singelton == null) initEngine();
	}

	public void shutdownFelix() throws BundleException {
		log(Logger.LOG_DEBUG, "---- Shutdown Felix ----");

		BundleCollection bc = singelton.getBundleCollection();
		if (bc == null || bc.felix == null) return;

		// stop
		BundleLoader.removeBundles(bc);

		// we give it some time
		try {
			Thread.sleep(5000);
		}
		catch (InterruptedException e) {
		}

		BundleUtil.stop(felix, false);
	}

	public static void main(String[] args) throws IOException {
		createBundleFromSource();

	}

	public static File createBundleFromSource() throws IOException {

		// Users/mic/Projects/Lucee/Lucee6/core/target/classes/META-INF/MANIFEST.MF
		String resPrefix = "../../../";
		String pathClas = "../core/target/classes/";
		String pathCfml = "../core/src/main/cfml/";
		String pathJava = "../core/src/main/java/";
		String pathLpom = "./pom.xml";

		String s = Util._getSystemPropOrEnvVar(ARG_PROJECT_DIR, "");
		if (!s.isEmpty()) {
			pathCfml = Paths.get(s, "/core/src/main/cfml/").toString();
			pathJava = Paths.get(s, "/core/src/main/java/").toString();
			pathLpom = Paths.get(s, "/loader/pom.xml").toString();
			pathClas = Paths.get(s, "/core/target/classes/").toString();
			s = Util._getSystemPropOrEnvVar(ARG_CLASSES_DIR, "");
			if (!s.isEmpty()) {
				// Lucee core classes should be in $ARG_CLASSES_DIR/core
				pathClas = Paths.get(s, "core").toString();
			}
		}

		String pathJres = Paths.get(pathJava, "resource/").toString();

		// LUCEE_CLASS_DIR allows to set custom compiler output directory for embedded mode if it is not at
		// ${LUCEE_SOURCE_DIR}/core/target/classes, e.g.
		// LUCEE_CLASS_DIR=/workspace/src/lucee/idea-compiler-output-6/production/core
		File classesDirectory = load("classes directory", "the directory containg all compiled class files from the core project", "LUCEE_CLASS_DIR", pathClas,
				resPrefix + pathClas, true);
		System.out.println("LUCEE_CLASS_DIR: " + classesDirectory);

		// read source cfml directory
		File sourceCfml = load("source directory", "the directory containg all CFML source files from the core project", "SOURCE_DIRECTORY", pathCfml, resPrefix + pathCfml, true);
		System.out.println("SOURCE_DIRECTORY: " + sourceCfml);

		// read source java directory
		File sourceJava = load("source java directory", "the directory containing Java source files from the core project", "SOURCE_JAVA_DIR", pathJava, resPrefix + pathJava,
				true);
		System.out.println("SOURCE_JAVA_DIR: " + sourceJava);

		File resourceJava = load("resource java directory", "the directory containing resources in the Java source of the core project", "", pathJres,
				resPrefix + pathJava + "resource/", true);
		System.out.println("RESOURCE_JAVA_DIR: " + resourceJava);

		// read POM File
		File pomFile = load("pom file", "the pom.xml file from the core project", "POM_FILE", pathLpom, "../../../pom.xml", false);
		System.out.println("POM: " + pomFile);

		// if (true) return null;
		File manifestFile = new File(sourceJava, "META-INF/MANIFEST.MF");

		Manifest manifest = new Manifest();
		// Assuming the manifest file is correct and fully prepared for OSGi
		try (InputStream is = new FileInputStream(manifestFile)) {
			manifest.read(is);
		}
		Attributes main = manifest.getMainAttributes();
		String bundleName = main.getValue("Bundle-SymbolicName");
		String bundleVersion = readVersionFromPOM(pomFile);
		System.out.println("VERSION: " + bundleVersion);

		main.put(new Attributes.Name("Bundle-Version"), bundleVersion);

		File bundleFile = File.createTempFile(bundleName + "-", ".lco");
		bundleFile.deleteOnExit();
		// bundleFile = new File("/Users/mic/tmp8/" + bundleName + "-" + bundleVersion + "-" +
		// System.currentTimeMillis() + ".lco");

		System.out.println("LCO: " + bundleFile);

		// Output file for the JAR
		JarOutputStream jos = null;
		// FileInputStream fis = null;

		try {
			jos = new JarOutputStream(new FileOutputStream(bundleFile), manifest);
			addDirectoryToJar(jos, classesDirectory, "", new FilenameFilter() {

				@Override
				public boolean accept(File dir, String name) {
					return !name.equals(".DS_Store");
					// return !name.equals(".DS_Store") && !name.endsWith(".java");
				}
			});

			// TODO this does not all copied in the right place
			addDirectoryToJar(jos, sourceCfml, "resource/", new FilenameFilter() {

				@Override
				public boolean accept(File dir, String name) {
					return !name.endsWith(".DS_Store") && !name.endsWith(".java");
				}
			});

			addDirectoryToJar(jos, resourceJava, "resource/", new FilenameFilter() {

				@Override
				public boolean accept(File dir, String name) {
					return !name.endsWith(".DS_Store") && !name.endsWith(".java");
				}
			});

			File f = new File(sourceJava, "default.properties");
			addFileToJar(jos, f, "");

			f = new File(sourceJava, "License.txt");
			addFileToJar(jos, f, "");
		}
		finally {
			// Util.closeEL(fis);
			Util.closeEL(jos);
		}
		return bundleFile;
	}

	private static File load(String subject, String desc, String envVarName, String relpath, String relResource, boolean dir) throws IOException {
		String env = Util._getSystemPropOrEnvVar(envVarName, "");
		File file;
		if (!Util.isEmpty(env)) {
			file = new File(env.trim());
			if ((dir && !file.isDirectory()) || (!dir && !file.isFile()))
				throw new IOException("the " + subject + " [" + file + "] (" + desc + ") you have defined via enviroment variable [" + envVarName + "] does not exist!");
		}
		// try to figure out based on current location
		else {
			// try to load it relative with java.io.File
			file = new File(relpath).getCanonicalFile(); // should work

			// try to load via resource
			if ((dir && !file.isDirectory()) || (!dir && !file.isFile())) {
				file = null;
				URL res = new TP().getClass().getClassLoader().getResource("");
				File f;
				try {
					f = new File(res.toURI());
					if (f.isDirectory()) {
						file = new File(f, relResource).getCanonicalFile();
					}
				}
				catch (URISyntaxException e) {
				}
			}
			if (file == null || ((dir && !file.isDirectory()) || (!dir && !file.isFile()))) {
				throw new IOException("could not find the " + subject + " (" + desc + "), please set the enviroment variable [" + envVarName + "] that points to it.");
			}
		}
		return file;
	}

	private static String readVersionFromPOM(File pomFile) throws IOException {
		String versionTagStart = "<version>";
		String versionTagEnd = "</version>";
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Util.copy(new FileInputStream(pomFile), baos, true, true);
		String xml = new String(baos.toByteArray());
		int startIndex = xml.indexOf(versionTagStart) + versionTagStart.length();
		int endIndex = xml.indexOf(versionTagEnd);

		if (startIndex < versionTagStart.length() || endIndex == -1) {
			throw new IOException("Version tag not found");
		}

		return xml.substring(startIndex, endIndex).trim();
	}

	private static void addFileToJar(JarOutputStream jos, File file, String archivePath) {
		JarEntry entry = new JarEntry(archivePath + file.getName());
		entry.setTime(file.lastModified());
		try {
			jos.putNextEntry(entry);
			// Write file to JAR
			try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
				byte[] buffer = new byte[4096];
				int count;
				while ((count = bis.read(buffer)) != -1) {
					jos.write(buffer, 0, count);
				}
			}
			jos.closeEntry();
		}
		catch (Exception ex) {
		}
	}

	private static void addDirectoryToJar(JarOutputStream jos, File folder, String parentEntryName, FilenameFilter filter) throws IOException {

		// Recursively add files to the jar
		String name;
		for (File file: folder.listFiles()) {
			if (file.isDirectory()) {
				name = (parentEntryName + file.getName() + "/").replace("//", "/");
				// System.out.println("dir:" + name);
				addDirectoryToJar(jos, file, name, filter); // Recursive call
			}
			else {
				name = (parentEntryName + file.getName()).replace("//", "/");
				// System.out.println(name);
				if ((filter != null && !filter.accept(folder, name)) || name.equals(JarFile.MANIFEST_NAME)) {
					// System.out.println(" - skipped");
					continue; // Skip the manifest file since it's already added
				}
				addFileToJar(jos, file, parentEntryName);
			}
		}
	}

	private void initEngine() throws ServletException {

		final Version coreVersion = VersionInfo.getIntVersion();
		final long coreCreated = VersionInfo.getCreateTime();

		// get newest lucee version as file
		File patcheDir = null;
		try {
			patcheDir = getPatchDirectory();
			log(org.apache.felix.resolver.Logger.LOG_DEBUG, "lucee-server-root:" + patcheDir.getParent());
		}
		catch (final IOException e) {
			throw new ServletException(e);
		}
		File lucee = null;

		if (isEmbeddedMode()) embedded = true;

		if (embedded) {
			try {
				lucee = createBundleFromSource();
			}
			catch (IOException e) {
				throw new ServletException(e);
			}
		}
		String tmp = Util._getSystemPropOrEnvVar("lucee.version", null);
		Version specificVersion = (!Util.isEmpty(tmp, true)) ? toVersion(tmp, null) : null;
		// a specific version cannot be older than the core version
		if (specificVersion != null) {
			if (Util.isNewerThan(MIN_VERSION, specificVersion)) {
				log(org.apache.felix.resolver.Logger.LOG_ERROR, "the version defined [" + specificVersion + "] cannot be used, version cannot be older than [" + MIN_VERSION + "]");
				specificVersion = null;
			}
			else {
				log(org.apache.felix.resolver.Logger.LOG_DEBUG, "specific version [" + specificVersion + "] defined.");
			}
		}

		// read lucee core from patch directory
		if (lucee == null) {

			// load a specific version
			Version pv;
			final File[] patches = PATCH_ENABLED ? patcheDir.listFiles(new ExtensionFilter(new String[] { ".lco" })) : null;
			if (patches != null) {
				for (final File patch: patches) {
					// invalid patches
					if (patch.getName().startsWith("tmp.lco") || patch.length() < 1000000L) {
						log(org.apache.felix.resolver.Logger.LOG_INFO, "delete [" + patch + "]");
						patch.delete();
						continue;
					}

					pv = toVersion(patch.getName(), VERSION_ZERO);

					// specific version
					if (specificVersion != null) {
						if (pv.equals(specificVersion)) {
							lucee = patch;
							log(org.apache.felix.resolver.Logger.LOG_DEBUG, "specific version [" + specificVersion + "] is used.");
							break;
						}
						continue;
					}

					// newest version
					if (lucee == null || Util.isNewerThan(pv, toVersion(lucee.getName(), VERSION_ZERO))) lucee = patch;
				}
			}

			if (specificVersion == null && lucee != null && Util.isNewerThan(coreVersion, toVersion(lucee.getName(), VERSION_ZERO))) {
				log(org.apache.felix.resolver.Logger.LOG_INFO, "ignoring version from patch folder, it is to old");
				lucee = null;
			}
		}
		// Load Lucee
		// URL url=null;
		if (lucee == null && specificVersion != null) {
			InputStream is = null;
			OutputStream os = null;
			try {
				is = new MavenUpdateProvider().getCore(specificVersion);
				lucee = new File(patcheDir, specificVersion + ".lco");
				Util.copy(is, os = new FileOutputStream(lucee));
			}
			catch (Exception e) {
				log(e);
				e.printStackTrace();// TODO remove
			}
			finally {
				Util.closeEL(is, os);
			}
		}

		try {

			// Load core version from jar when no patch available
			if (lucee == null) {

				log(org.apache.felix.resolver.Logger.LOG_DEBUG, "Load built-in Core");
				final String coreExt = "lco";
				// copy core
				InputStream is = null;
				OutputStream os = null;
				final File rc = new File(getTempDirectory(), "tmp_" + System.currentTimeMillis() + "." + coreExt);
				try {
					is = new TP().getClass().getResourceAsStream("/core/core." + coreExt);
					if (is == null) {
						// check for custom path of Lucee core
						String s = System.getProperty("lucee.core.path");
						if (s != null) {
							File dir = new File(s);
							File[] files = dir.listFiles(new ExtensionFilter(new String[] { coreExt }));
							if (files.length > 0) {
								is = new FileInputStream(files[0]);
							}
						}
					}

					if (is != null) {
						os = new BufferedOutputStream(new FileOutputStream(rc));
						copy(is, os);
					}
					else {
						log(org.apache.felix.resolver.Logger.LOG_ERROR,
								"/core/core." + coreExt + " not found at " + TP.class.getProtectionDomain().getCodeSource().getLocation().getPath());
					}
				}
				finally {
					closeEL(is);
					closeEL(os);
				}

				CFMLEngine engine = null;
				String v = getVersion(rc);
				lucee = new File(patcheDir, v + "." + coreExt);
				try {
					is = new FileInputStream(rc);
					os = new BufferedOutputStream(new FileOutputStream(lucee));
					copy(is, os);
				}
				finally {
					closeEL(is);
					closeEL(os);
					rc.delete();
				}

				engine = _getCore(lucee);

				setEngine(engine);
			}
			else {
				bundleCollection = BundleLoader.loadBundles(this, getFelixCacheDirectory(), getBundleDirectory(), lucee, bundleCollection);
				// bundle=loadBundle(lucee);
				log(org.apache.felix.resolver.Logger.LOG_DEBUG, "Loaded bundle: [" + bundleCollection.core.getSymbolicName() + "]");
				setEngine(getEngine(bundleCollection));
				log(org.apache.felix.resolver.Logger.LOG_DEBUG, "Loaded engine: [" + singelton + "]");
			}
			version = singelton.getInfo().getVersion();

			log(org.apache.felix.resolver.Logger.LOG_DEBUG, "Loaded Lucee Version [" + singelton.getInfo().getVersion() + "]");
		}
		catch (final InvocationTargetException e) {
			log(e.getTargetException());
			throw new ServletException(e.getTargetException());
		}
		catch (final Exception e) {
			e.printStackTrace();
			throw new ServletException(e);
		}
	}

	private static String getVersion(File file) throws IOException, BundleException {
		JarFile jar = new JarFile(file);
		try {
			Manifest manifest = jar.getManifest();
			Attributes attrs = manifest.getMainAttributes();
			return attrs.getValue("Bundle-Version");
		}
		finally {
			jar.close();
		}
	}

	private static CFMLEngineWrapper setEngine(final CFMLEngine engine) {
		// new RuntimeException("setEngine").printStackTrace();
		if (singelton == null) singelton = new CFMLEngineWrapper(engine);
		else if (!singelton.isIdentical(engine)) {
			singelton.setEngine(engine); // reset of the old is made before
		}
		else {
			// new RuntimeException("useless call").printStackTrace();
		}

		return singelton;
	}

	public Felix getFelix(final File cacheRootDir, Map<String, Object> config) throws BundleException {

		if (config == null) config = new HashMap<String, Object>();

		// Log Level
		int logLevel = 1; // 1 = error, 2 = warning, 3 = information, and 4 = debug
		String strLogLevel = getSystemPropOrEnvVar("felix.log.level", null);
		if (Util.isEmpty(strLogLevel)) strLogLevel = (String) config.get("felix.log.level");

		if (!Util.isEmpty(strLogLevel)) {
			if ("0".equalsIgnoreCase(strLogLevel)) logLevel = 0;
			else if ("error".equalsIgnoreCase(strLogLevel) || "1".equalsIgnoreCase(strLogLevel)) logLevel = 1;
			else if ("warning".equalsIgnoreCase(strLogLevel) || "2".equalsIgnoreCase(strLogLevel)) logLevel = 2;
			else if ("info".equalsIgnoreCase(strLogLevel) || "information".equalsIgnoreCase(strLogLevel) || "3".equalsIgnoreCase(strLogLevel)) logLevel = 3;
			else if ("debug".equalsIgnoreCase(strLogLevel) || "4".equalsIgnoreCase(strLogLevel)) logLevel = 4;
		}
		config.put("felix.log.level", "" + logLevel);

		if (logger != null) {
			if (logLevel == 2) logger.setLogLevel(Logger.LOG_WARNING);
			else if (logLevel == 3) logger.setLogLevel(Logger.LOG_INFO);
			else if (logLevel == 4) logger.setLogLevel(Logger.LOG_DEBUG);
			else logger.setLogLevel(Logger.LOG_ERROR);
		}

		if (logger != null) {
			if (logLevel == 2) logger.setLogLevel(Logger.LOG_WARNING);
			else if (logLevel == 3) logger.setLogLevel(Logger.LOG_INFO);
			else if (logLevel == 4) logger.setLogLevel(Logger.LOG_DEBUG);
			else logger.setLogLevel(Logger.LOG_ERROR);
		}

		// Allow felix.cache.locking to be overridden by env var (true/false)
		// Enables or disables bundle cache locking, which is used to prevent concurrent access to the
		// bundle cache.

		extend(config, "felix.cache.locking", null, false);
		extend(config, "org.osgi.framework.executionenvironment", null, false);
		extend(config, "org.osgi.framework.storage", null, false);
		extend(config, "org.osgi.framework.storage.clean", Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT, false);
		extend(config, Constants.FRAMEWORK_BUNDLE_PARENT, Constants.FRAMEWORK_BUNDLE_PARENT_FRAMEWORK, false);

		boolean isNew = false;
		// felix.cache.rootdir
		if (Util.isEmpty((String) config.get("felix.cache.rootdir"))) {
			if (!cacheRootDir.exists()) {
				cacheRootDir.mkdirs();
				isNew = true;
			}
			if (cacheRootDir.isDirectory()) config.put("felix.cache.rootdir", cacheRootDir.getAbsolutePath());
		}

		extend(config, Constants.FRAMEWORK_BOOTDELEGATION, null, true);
		extend(config, Constants.FRAMEWORK_SYSTEMPACKAGES, null, true);
		extend(config, Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, null, true);
		extend(config, "felix.cache.filelimit", null, false);
		extend(config, "felix.cache.bufsize", null, false);
		extend(config, "felix.bootdelegation.implicit", null, false);
		extend(config, "felix.systembundle.activators", null, false);
		extend(config, "org.osgi.framework.startlevel.beginning", null, false);
		extend(config, "felix.startlevel.bundle", null, false);
		extend(config, "felix.service.urlhandlers", null, false);
		extend(config, "felix.auto.deploy.dir", null, false);
		extend(config, "felix.auto.deploy.action", null, false);
		extend(config, "felix.shutdown.hook", null, false);

		if (logger != null) config.put("felix.log.logger", logger);
		// TODO felix.log.logger

		// remove any empty record, this can produce trouble
		{
			final Iterator<Entry<String, Object>> it = config.entrySet().iterator();
			Entry<String, Object> e;
			Object v;
			while (it.hasNext()) {
				e = it.next();
				v = e.getValue();
				if (v == null || v.toString().isEmpty()) it.remove();
			}
		}

		final StringBuilder sb = new StringBuilder("Loading felix with config:");
		final Iterator<Entry<String, Object>> it = config.entrySet().iterator();
		Entry<String, Object> e;
		while (it.hasNext()) {
			e = it.next();
			sb.append("\n- ").append(e.getKey()).append(':').append(e.getValue());
		}
		// log(Logger.LOG_INFO, sb.toString());

		felix = new Felix(config);
		try {
			felix.start();
		}
		catch (BundleException be) {
			// this could be cause by an invalid felix cache, so we simply delete it and try again
			if (!isNew && "Error creating bundle cache.".equals(be.getMessage())) {
				Util.deleteContent(cacheRootDir, null);

			}

		}

		return felix;
	}

	private static void extend(Map<String, Object> config, String name, String defaultValue, boolean add) {
		String addional = getSystemPropOrEnvVar(name, null);
		if (Util.isEmpty(addional, true)) {
			if (Util.isEmpty(defaultValue, true)) return;
			addional = defaultValue.trim();
		}
		if (add) {
			String existing = (String) config.get(name);
			if (!Util.isEmpty(existing, true)) config.put(name, existing.trim() + "," + addional.trim());
			else config.put(name, addional.trim());
		}
		else {
			config.put(name, addional.trim());
		}
	}

	protected static String getSystemPropOrEnvVar(String name, String defaultValue) {
		return Util._getSystemPropOrEnvVar(name, defaultValue);
	}

	public boolean isEmbeddedMode() {
		String s = Util._getSystemPropOrEnvVar(ARG_PROJECT_DIR, null);
		if (s == null) return false;
		boolean result = (new File(s)).isDirectory();
		if (!result) System.out.println(ARG_PROJECT_DIR + " is set but is not pointing to a valid directory (" + s + ")");
		return result;
	}

	public void log(final Throwable t) {
		if (logger != null) logger.log(Logger.LOG_ERROR, "", t);
	}

	public void log(final int level, final String msg) {
		if (logger != null) logger.log(level, msg);
	}

	private CFMLEngine _getCore(File rc) throws IOException, BundleException, ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException,
			IllegalAccessException, InvocationTargetException {
		bundleCollection = BundleLoader.loadBundles(this, getFelixCacheDirectory(), getBundleDirectory(), rc, bundleCollection);
		return getEngine(bundleCollection);

	}

	public boolean update(final Password password, final Identification id) throws IOException, ServletException {
		if (!singelton.can(CFMLEngine.CAN_UPDATE, password)) throw new IOException("Access denied to update CFMLEngine");
		// new RunUpdate(this).start();
		return _update(id);
	}

	public boolean restart(final Password password) throws IOException, ServletException {
		if (!singelton.can(CFMLEngine.CAN_RESTART_ALL, password)) throw new IOException("Access denied to restart CFMLEngine");

		return _restart();
	}

	public boolean restart(final String configId, final Password password) throws IOException, ServletException {
		if (!singelton.can(CFMLEngine.CAN_RESTART_CONTEXT, password))// TODO restart single context
			throw new IOException("Access denied to restart CFML Context (configId:" + configId + ")");

		return _restart();
	}

	/**
	 * restart the cfml engine
	 *
	 * @param password
	 * @return has updated
	 * @throws IOException
	 * @throws ServletException
	 */
	private synchronized boolean _restart() throws ServletException {
		if (singelton != null) singelton.reset();

		initEngine();

		ConfigServer cs = getConfigServer(singelton);
		if (cs != null) {
			Log log = cs.getLog("application");
			log.info("loader", "Lucee restarted");
		}
		System.gc();
		return true;
	}

	/**
	 * updates the engine when an update is available
	 *
	 * @return has updated
	 * @throws IOException
	 * @throws ServletException
	 */
	private boolean _update(final Identification id) throws IOException, ServletException {

		final File newLucee = downloadCore(id);
		if (newLucee == null) return false;

		if (singelton != null) singelton.reset();

		final Version v = null;
		try {

			bundleCollection = BundleLoader.loadBundles(this, getFelixCacheDirectory(), getBundleDirectory(), newLucee, bundleCollection);
			final CFMLEngine e = getEngine(bundleCollection);
			if (e == null) throw new IOException("Failed to load engine");
			version = e.getInfo().getVersion();
			// engine = e;
			setEngine(e);
			// e.reset();
			callListeners(e);

			ConfigServer cs = getConfigServer(e);
			if (cs != null) {
				Log log = cs.getLog("deploy");
				log.info("loader", "Lucee Version [" + v + "] installed");
			}

		}
		catch (final Exception e) {
			System.gc();
			try {
				newLucee.delete();
			}
			catch (final Exception ee) {
			}
			log(e);
			e.printStackTrace();
			return false;
		}

		log(Logger.LOG_DEBUG, "Version (" + v + ")installed");
		return true;
	}

	private ConfigServer getConfigServer(CFMLEngine engine) {
		if (engine == null) return null;
		if (engine instanceof CFMLEngineWrapper) engine = ((CFMLEngineWrapper) engine).getEngine();

		try {
			Method m = engine.getClass().getDeclaredMethod("getConfigServerImpl", new Class[] {});
			m.setAccessible(true);
			return (ConfigServer) m.invoke(engine, new Object[] {});
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public File downloadBundle(final String symbolicName, final String symbolicVersion, Identification id) throws IOException {

		final File jarDir = getBundleDirectory();

		// before we download we check if we have it bundled
		File jar = deployBundledBundle(jarDir, symbolicName, symbolicVersion);
		if (jar != null && jar.isFile()) return jar;
		if (jar != null) {
			log(Logger.LOG_INFO, jar + " should exist but does not (exist?" + jar.exists() + ";file?" + jar.isFile() + ";hidden?" + jar.isHidden() + ")");
		}

		String str = Util._getSystemPropOrEnvVar("lucee.enable.bundle.download", null);
		if (str != null && ("false".equalsIgnoreCase(str) || "no".equalsIgnoreCase(str))) { // we do not use CFMLEngine to cast, because the engine may not exist yet
			throw (new RuntimeException("Lucee is missing the Bundle jar, " + symbolicName + ":" + symbolicVersion
					+ ", and has been prevented from downloading it. If this jar is not a core jar, it will need to be manually downloaded and placed in the {{lucee-server}}/context/bundles directory."));
		}

		jar = new File(jarDir, symbolicName.replace('.', '-') + "-" + symbolicVersion.replace('.', '-') + (".jar"));

		final URL updateProvider = getUpdateLocation();
		if (id == null && singelton != null) id = singelton.getIdentification();

		final URL updateUrl = new URL(updateProvider, "/rest/update/provider/download/" + symbolicName + "/" + symbolicVersion + "/" + (id != null ? id.toQueryString() : "")
				+ (id == null ? "?" : "&") + "allowRedirect=true&jv=" + System.getProperty("java.version")

		);
		log(Logger.LOG_INFO, "Downloading bundle [" + symbolicName + ":" + symbolicVersion + "] from " + updateUrl + " and copying to " + jar);

		int code;
		HttpURLConnection conn;
		try {
			conn = (HttpURLConnection) updateUrl.openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(10000);
			conn.connect();
			code = conn.getResponseCode();
		}
		catch (UnknownHostException e) {
			log(Logger.LOG_ERROR, "Failed to download the bundle  [" + symbolicName + ":" + symbolicVersion + "] from [" + updateUrl + "] and copy to [" + jar + "]"); // MUST
																																										// remove
			throw new IOException("Failed to download the bundle  [" + symbolicName + ":" + symbolicVersion + "] from [" + updateUrl + "] and copy to [" + jar + "]", e);
		}
		// the update provider is not providing a download for this
		if (code != 200) {

			// the update provider can also provide a different (final) location for this
			int count = 1;
			while ((code == 302 || code == 301) && count++ <= MAX_REDIRECTS) {
				String location = conn.getHeaderField("Location");
				// just in case we check invalid names
				if (location == null) location = conn.getHeaderField("location");
				if (location == null) location = conn.getHeaderField("LOCATION");
				log(Logger.LOG_INFO, "download redirected:" + location);

				conn.disconnect();
				URL url = new URL(location);
				try {
					conn = (HttpURLConnection) url.openConnection();
					conn.setRequestMethod("GET");
					conn.setConnectTimeout(10000);
					conn.connect();
					code = conn.getResponseCode();
				}
				catch (final UnknownHostException e) {
					log(e);
					throw new IOException("Failed to download the bundle  [" + symbolicName + ":" + symbolicVersion + "] from [" + location + "] and copy to [" + jar + "]", e);
				}
			}

			// no download available!
			if (code != 200) {
				final String msg = "Failed to download the bundle for [" + symbolicName + "] in version [" + symbolicVersion + "] from [" + updateUrl
						+ "], please download manually and copy to [" + jarDir + "]";
				log(Logger.LOG_ERROR, msg);
				conn.disconnect();
				throw new IOException(msg);
			}

		}

		// if(jar.createNewFile()) {
		copy((InputStream) conn.getContent(), new FileOutputStream(jar));
		conn.disconnect();
		return jar;
		/*
		 * } else { throw new IOException("File ["+jar.getName()+"] already exists, won't copy new one"); }
		 */
	}

	private File deployBundledBundle(File bundleDirectory, String symbolicName, String symbolicVersion) {
		String sub = "bundles/";
		String nameAndVersion = symbolicName + "|" + symbolicVersion;
		String osgiFileName = symbolicName + "-" + symbolicVersion + ".jar";
		String pack20Ext = ".jar.pack.gz";
		boolean isPack200 = false;

		// first we look for an exact match
		InputStream is = getClass().getResourceAsStream("bundles/" + osgiFileName);
		if (is == null) is = getClass().getResourceAsStream("/bundles/" + osgiFileName);

		if (is != null) log(Logger.LOG_DEBUG, "Found ]/bundles/" + osgiFileName + "] in lucee.jar");
		else log(Logger.LOG_INFO, "Could not find [/bundles/" + osgiFileName + "] in lucee.jar");

		if (is == null) {
			is = getClass().getResourceAsStream("bundles/" + osgiFileName + pack20Ext);
			if (is == null) is = getClass().getResourceAsStream("/bundles/" + osgiFileName + pack20Ext);
			isPack200 = true;

			if (is != null) log(Logger.LOG_DEBUG, "Found [/bundles/" + osgiFileName + pack20Ext + "] in lucee.jar");
			else log(Logger.LOG_INFO, "Could not find [/bundles/" + osgiFileName + pack20Ext + "] in lucee.jar");
		}
		if (is != null) {
			File temp = null;
			try {
				// copy to temp file
				temp = File.createTempFile("bundle", ".tmp");
				log(Logger.LOG_DEBUG, "Copying [lucee.jar!/bundles/" + osgiFileName + pack20Ext + "] to [" + temp + "]");
				Util.copy(new BufferedInputStream(is), new FileOutputStream(temp), true, true);

				if (isPack200) {
					File temp2 = File.createTempFile("bundle", ".tmp2");
					Pack200Util.pack2Jar(temp, temp2);
					log(Logger.LOG_DEBUG, "Upack [" + temp + "] to [" + temp2 + "]");
					temp.delete();
					temp = temp2;
				}

				// adding bundle
				File trg = new File(bundleDirectory, osgiFileName);
				fileMove(temp, trg);
				log(Logger.LOG_DEBUG, "Adding bundle [" + symbolicName + "] in version [" + symbolicVersion + "] to [" + trg + "]");
				return trg;
			}
			catch (IOException ioe) {
				ioe.printStackTrace();
			}
			finally {
				if (temp != null && temp.exists()) temp.delete();
			}
		}

		// now we search the current jar as an external zip what is slow (we do not support pack200 in this
		// case)
		// this also not works with windows
		if (isWindows()) return null;
		ZipEntry entry;
		File temp;
		ZipInputStream zis = null;
		try {
			CodeSource src = CFMLEngineFactory.class.getProtectionDomain().getCodeSource();
			if (src == null) return null;
			URL loc = src.getLocation();

			zis = new ZipInputStream(loc.openStream());
			String path, name, bundleInfo;
			int index;
			while ((entry = zis.getNextEntry()) != null) {
				temp = null;
				path = entry.getName().replace('\\', '/');
				if (path.startsWith("/")) path = path.substring(1); // some zip path start with "/" some not
				isPack200 = false;
				if (path.startsWith(sub) && (path.endsWith(".jar") /* || (isPack200=path.endsWith(".jar.pack.gz")) */)) { // ignore non jar files or file from elsewhere
					index = path.lastIndexOf('/') + 1;
					if (index == sub.length()) { // ignore sub directories
						name = path.substring(index);
						temp = null;
						try {
							temp = File.createTempFile("bundle", ".tmp");
							Util.copy(zis, new FileOutputStream(temp), false, true);

							/*
							 * if(isPack200) { File temp2 = File.createTempFile("bundle", ".tmp2"); Pack200Util.pack2Jar(temp,
							 * temp2); temp.delete(); temp=temp2; name=name.substring(0,name.length()-".pack.gz".length()); }
							 */

							bundleInfo = BundleLoader.loadBundleInfo(temp);
							if (bundleInfo != null && nameAndVersion.equals(bundleInfo)) {
								File trg = new File(bundleDirectory, name);
								temp.renameTo(trg);
								log(Logger.LOG_DEBUG, "Adding bundle [" + symbolicName + "] in version [" + symbolicVersion + "] to [" + trg + "]");

								return trg;
							}
						}
						finally {
							if (temp != null && temp.exists()) temp.delete();
						}

					}
				}
				zis.closeEntry();
			}
		}
		catch (Throwable t) {
			if (t instanceof ThreadDeath) throw (ThreadDeath) t;
		}
		finally {
			Util.closeEL(zis);
		}
		return null;
	}

	// FUTURE move to Util class
	private final static void fileMove(File src, File dest) throws IOException {
		boolean moved = src.renameTo(dest);
		if (!moved) {
			BufferedInputStream is = new BufferedInputStream(new FileInputStream(src));
			BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(dest));
			try {
				Util.copy(is, os, false, false); // is set false here, because copy does not close in case of an exception
			}
			finally {
				closeEL(is);
				closeEL(os);
			}
			if (!src.delete()) src.deleteOnExit();
		}
	}

	private boolean isWindows() {
		String os = System.getProperty("os.name").toLowerCase();
		return os.startsWith("windows");
	}

	private File downloadCore(Identification id) throws IOException {
		final URL updateProvider = getUpdateLocation();

		if (id == null && singelton != null) id = singelton.getIdentification();

		// only happens when the code runs from the debug project
		if (version == null) version = getInstance().getInfo().getVersion();

		final URL infoUrl = new URL(updateProvider, "/rest/update/provider/update-for/" + version.toString() + (id != null ? id.toQueryString() : ""));

		log(Logger.LOG_DEBUG, "Checking for core update at [" + updateProvider + "]");

		String strAvailableVersion = toString((InputStream) infoUrl.getContent()).trim();
		log(Logger.LOG_DEBUG, "Update provider reports an updated core version available [" + strAvailableVersion + "] ");

		strAvailableVersion = CFMLEngineFactorySupport.removeQuotes(strAvailableVersion, true);

		if (strAvailableVersion.length() == 0 || !Util.isNewerThan(toVersion(strAvailableVersion, VERSION_ZERO), version)) {
			log(Logger.LOG_DEBUG, "There is no newer Version available");
			return null;
		}

		log(Logger.LOG_INFO, "Found a newer Version \n - current Version [" + version.toString() + "]\n - available Version [" + strAvailableVersion + "]");

		final URL updateUrl = new URL(updateProvider,
				"/rest/update/provider/download/" + strAvailableVersion + (id != null ? id.toQueryString() : "") + (id == null ? "?" : "&") + "allowRedirect=true");
		log(Logger.LOG_INFO, "Downloading core update from [" + updateUrl + "]");

		// local resource
		final File patchDir = getPatchDirectory();
		final File newLucee = new File(patchDir, strAvailableVersion + (".lco"));
		////

		int code;
		HttpURLConnection conn;
		try {
			conn = (HttpURLConnection) updateUrl.openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(10000);
			conn.connect();
			code = conn.getResponseCode();
		}
		catch (final UnknownHostException e) {
			log(e);
			throw e;
		}

		// the update provider is not providing a download for this
		if (code != 200) {

			// the update provider can also provide a different (final) location for this
			if (code == 302) {
				String location = conn.getHeaderField("Location");
				// just in case we check invalid names
				if (location == null) location = conn.getHeaderField("location");
				if (location == null) location = conn.getHeaderField("LOCATION");
				log(Logger.LOG_DEBUG, "download redirected to " + location);

				conn.disconnect();
				URL url = new URL(location);
				try {
					conn = (HttpURLConnection) url.openConnection();
					conn.setRequestMethod("GET");
					conn.setConnectTimeout(10000);
					conn.connect();
					code = conn.getResponseCode();
				}
				catch (final UnknownHostException e) {
					log(e);
					throw e;
				}
			}

			// no download available!
			if (code != 200) {
				final String msg = "Lucee failed to download the core for version [" + version.toString() + "] from " + updateUrl + ", please download it manually and copy to ["
						+ patchDir + "]";
				log(Logger.LOG_ERROR, msg);
				conn.disconnect();
				throw new IOException(msg);
			}
		}

		// copy it to local directory
		if (newLucee.createNewFile()) {
			copy((InputStream) conn.getContent(), new FileOutputStream(newLucee));
			conn.disconnect();

			// when it is a loader extract the core from it
			File tmp = extractCoreIfLoader(newLucee);
			if (tmp != null) {
				log(Logger.LOG_DEBUG, "Extract core from loader");

				newLucee.delete();
				tmp.renameTo(newLucee);
				tmp.delete();

			}
		}
		else {
			conn.disconnect();
			log(Logger.LOG_DEBUG, "File for new Version already exists, won't copy new one");
			return null;
		}
		return newLucee;
	}

	public static File extractCoreIfLoader(File file) {
		try {
			return _extractCoreIfLoader(file);
		}
		catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static File _extractCoreIfLoader(File file) throws IOException {
		JarFile jf = new JarFile(file);
		try {
			// is it a lucee loader ?
			String value = jf.getManifest().getMainAttributes().getValue("Main-Class");
			if (Util.isEmpty(value) || !value.equals("lucee.runtime.script.Main")) return null;

			// get the core file;
			JarEntry je = jf.getJarEntry("core/core.lco");
			if (je == null) return null;

			InputStream is = jf.getInputStream(je);
			File trg = File.createTempFile("lucee", ".lco");
			OutputStream os = new FileOutputStream(trg);
			try {
				Util.copy(is, os);
			}
			finally {
				Util.closeEL(is);
				Util.closeEL(os);
			}

			return trg;
		}
		finally {
			jf.close();
		}
	}

	public URL getUpdateLocation() throws MalformedURLException {
		URL location = singelton == null ? null : singelton.getUpdateLocation();

		// read location directly from xml
		if (location == null) {
			final InputStream is = null;

			try {
				final File xml = new File(getResourceRoot(), "context/lucee-server.xml");
				if (xml.exists() || xml.length() > 0) {
					final DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
					final DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
					final Document doc = dBuilder.parse(xml);
					final Element root = doc.getDocumentElement();

					final NodeList children = root.getChildNodes();

					for (int i = children.getLength() - 1; i >= 0; i--) {
						final Node node = children.item(i);
						if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals("update")) {
							final String loc = ((Element) node).getAttribute("location");
							if (!Util.isEmpty(loc)) location = new URL(loc);
						}
					}
				}

			}
			catch (final Throwable t) {
				t.printStackTrace();
			}
			finally {
				CFMLEngineFactorySupport.closeEL(is);
			}
		}

		// if there is no lucee-server.xml
		if (location == null) location = new URL(UPDATE_LOCATION);

		return location;
	}

	/**
	 * method to initialize an update of the CFML Engine. checks if there is a new Version and update it
	 * when a new version is available
	 *
	 * @param password password
	 * @return has updated
	 * @throws IOException io exception
	 * @throws ServletException servlet exception
	 */
	public boolean removeUpdate(final Password password) throws IOException, ServletException {
		if (!singelton.can(CFMLEngine.CAN_UPDATE, password)) throw new IOException("Access denied to update CFMLEngine");
		return removeUpdate();
	}

	/**
	 * method to initialize an update of the CFML Engine. checks if there is a new Version and update it
	 * when a new version is available
	 *
	 * @param password password for lucee
	 * @return has updated
	 * @throws IOException io exception
	 * @throws ServletException servlet exception
	 */
	public boolean removeLatestUpdate(final Password password) throws IOException, ServletException {
		if (!singelton.can(CFMLEngine.CAN_UPDATE, password)) throw new IOException("Access denied to update CFMLEngine");
		return removeLatestUpdate();
	}

	/**
	 * updates the engine when an update is available
	 *
	 * @return has updated
	 * @throws IOException io exception
	 * @throws ServletException servlet exception
	 */
	private boolean removeUpdate() throws IOException, ServletException {
		final File patchDir = getPatchDirectory();
		final File[] patches = patchDir.listFiles(new ExtensionFilter(new String[] { "rc", "rcs" }));

		for (int i = 0; i < patches.length; i++)
			if (!patches[i].delete()) patches[i].deleteOnExit();
		_restart();
		return true;
	}

	private boolean removeLatestUpdate() throws IOException, ServletException {
		final File patchDir = getPatchDirectory();
		final File[] patches = patchDir.listFiles(new ExtensionFilter(new String[] { ".lco" }));
		File patch = null;
		for (final File patche: patches)
			if (patch == null || Util.isNewerThan(toVersion(patche.getName(), VERSION_ZERO), toVersion(patch.getName(), VERSION_ZERO))) patch = patche;
		if (patch != null && !patch.delete()) patch.deleteOnExit();

		_restart();
		return true;
	}

	public String[] getInstalledPatches() throws ServletException, IOException {
		final File patchDir = getPatchDirectory();
		final File[] patches = patchDir.listFiles(new ExtensionFilter(new String[] { ".lco" }));

		final List<String> list = new ArrayList<String>();
		String name;
		final int extLen = "rc".length() + 1;
		for (final File patche: patches) {
			name = patche.getName();
			name = name.substring(0, name.length() - extLen);
			list.add(name);
		}
		final String[] arr = list.toArray(new String[list.size()]);
		Arrays.sort(arr);
		return arr;
	}

	/**
	 * call all registered listener for update of the engine
	 *
	 * @param engine
	 */
	private void callListeners(final CFMLEngine engine) {
		final Iterator<EngineChangeListener> it = listeners.iterator();
		while (it.hasNext())
			it.next().onUpdate();
	}

	public File getPatchDirectory() throws IOException {
		File pd = getDirectoryByPropOrEnv("lucee.patches.dir");
		if (pd != null) return pd;

		pd = new File(getResourceRoot(), "patches");
		if (!pd.exists()) pd.mkdirs();
		return pd;
	}

	public File getBundleDirectory() throws IOException {
		File bd = getDirectoryByPropOrEnv("lucee.bundles.dir");
		if (bd != null) return bd;

		bd = new File(getResourceRoot(), "bundles");
		if (!bd.exists()) bd.mkdirs();
		return bd;
	}

	public File getFelixCacheDirectory() throws IOException {
		return getResourceRoot();
		// File bd = new File(getResourceRoot(),"felix-cache");
		// if(!bd.exists())bd.mkdirs();
		// return bd;
	}

	/**
	 * return directory to lucee resource root
	 *
	 * @return lucee root directory
	 * @throws IOException exception thrown
	 */
	public File getResourceRoot() throws IOException {
		if (resourceRoot == null) {
			resourceRoot = new File(_getResourceRoot(), "lucee-server");
			if (!resourceRoot.exists()) resourceRoot.mkdirs();
		}
		return resourceRoot;
	}

	/**
	 * @return return running context root
	 * @throws IOException
	 * @throws IOException
	 */
	private File _getResourceRoot() throws IOException {

		// custom configuration
		if (luceeServerRoot == null) readInitParam(config);
		if (luceeServerRoot != null) return luceeServerRoot;

		File lbd = getDirectoryByPropOrEnv("lucee.base.dir"); // directory defined by the caller

		File root = lbd;
		// get the root directory
		if (root == null) root = getDirectoryByProp("jboss.server.home.dir"); // Jboss/Jetty|Tomcat
		if (root == null) root = getDirectoryByProp("jonas.base"); // Jonas
		if (root == null) root = getDirectoryByProp("catalina.base"); // Tomcat
		if (root == null) root = getDirectoryByProp("jetty.home"); // Jetty
		if (root == null) root = getDirectoryByProp("org.apache.geronimo.base.dir"); // Geronimo
		if (root == null) root = getDirectoryByProp("com.sun.aas.instanceRoot"); // Glassfish
		if (root == null) root = getDirectoryByProp("env.DOMAIN_HOME"); // weblogic
		if (root == null) root = getClassLoaderRoot(mainClassLoader).getParentFile().getParentFile();

		final File classicRoot = getClassLoaderRoot(mainClassLoader);

		// in case of a war file the server root need to be with the context
		if (lbd == null) {
			File webInf = getWebInfFolder(classicRoot);
			if (webInf != null) {
				root = webInf;
				if (!root.exists()) root.mkdir();
				log(Logger.LOG_DEBUG, "war-root-directory:" + root);
			}
		}

		log(Logger.LOG_DEBUG, "root-directory:" + root);

		if (root == null) throw new IOException("Can't locate the root of the servlet container, please define a location (physical path) for the server configuration"
				+ " with help of the servlet init param [lucee-server-directory] in the web.xml where the Lucee Servlet is defined" + " or the system property [lucee.base.dir].");

		final File modernDir = new File(root, "lucee-server");
		if (true) {
			// there is a server context in the old lucee location, move that one
			File classicDir;
			log(Logger.LOG_DEBUG, "classic-root-directory:" + classicRoot);
			boolean had = false;
			if (classicRoot.isDirectory() && (classicDir = new File(classicRoot, "lucee-server")).isDirectory()) {
				log(Logger.LOG_DEBUG, "had lucee-server classic" + classicDir);
				moveContent(classicDir, modernDir);
				had = true;
			}
			// there is a railo context
			if (!had && classicRoot.isDirectory() && (classicDir = new File(classicRoot, "railo-server")).isDirectory()) {
				log(Logger.LOG_DEBUG, "Had railo-server classic" + classicDir);
				// check if there is a Railo context
				copyRecursiveAndRename(classicDir, modernDir);
				// zip the railo-server di and delete it (optional)
				try {
					ZipUtil.zip(classicDir, new File(root, "railo-server-context-old.zip"));
					Util.delete(classicDir);
				}
				catch (final Throwable t) {
					t.printStackTrace();
				}
				// moveContent(classicDir,new File(root,"lucee-server"));
			}
		}

		return root;
	}

	private static File getWebInfFolder(File file) {
		File parent;
		while (file != null && !file.getName().equals("WEB-INF")) {
			parent = file.getParentFile();
			if (file.equals(parent)) return null; // this should not happen, simply to be sure
			file = parent;
		}
		return file;
	}

	private static void copyRecursiveAndRename(final File src, File trg) throws IOException {
		if (!src.exists()) return;

		if (src.isDirectory()) {
			if (!trg.exists()) trg.mkdirs();

			final File[] files = src.listFiles();
			for (final File file: files)
				copyRecursiveAndRename(file, new File(trg, file.getName()));
		}
		else if (src.isFile()) {
			if (trg.getName().endsWith(".rc") || trg.getName().startsWith(".")) return;

			if (trg.getName().equals("railo-server.xml")) {
				trg = new File(trg.getParentFile(), "lucee-server.xml");
				// cfLuceeConfiguration
				final FileInputStream is = new FileInputStream(src);
				final FileOutputStream os = new FileOutputStream(trg);
				try {
					String str = Util.toString(is);
					str = str.replace("<cfRailoConfiguration", "<!-- copy from Railo context --><cfLuceeConfiguration");
					str = str.replace("</cfRailoConfiguration", "</cfLuceeConfiguration");

					str = str.replace("<railo-configuration", "<!-- copy from Railo context --><cfLuceeConfiguration");
					str = str.replace("</railo-configuration", "</cfLuceeConfiguration");

					str = str.replace("{railo-config}", "{lucee-config}");
					str = str.replace("{railo-server}", "{lucee-server}");
					str = str.replace("{railo-web}", "{lucee-web}");
					str = str.replace("\"railo.commons.", "\"lucee.commons.");
					str = str.replace("\"railo.runtime.", "\"lucee.runtime.");
					str = str.replace("\"railo.cfx.", "\"lucee.cfx.");
					str = str.replace("/railo-context.ra", "/lucee-context.lar");
					str = str.replace("/railo-context", "/lucee");
					str = str.replace("railo-server-context", "lucee-server");
					str = str.replace("http://www.getrailo.org", "https://release.lucee.org");
					str = str.replace("http://www.getrailo.com", "https://release.lucee.org");

					final ByteArrayInputStream bais = new ByteArrayInputStream(str.getBytes());

					try {
						Util.copy(bais, os);
						bais.close();
					}
					finally {
						Util.closeEL(is, os);
					}
				}
				finally {
					Util.closeEL(is, os);
				}
				return;
			}

			final FileInputStream is = new FileInputStream(src);
			final FileOutputStream os = new FileOutputStream(trg);
			try {
				Util.copy(is, os);
			}
			finally {
				Util.closeEL(is, os);
			}
		}
	}

	private void moveContent(final File src, final File trg) throws IOException {
		if (src.isDirectory()) {
			final File[] children = src.listFiles();
			if (children != null) for (final File element: children)
				moveContent(element, new File(trg, element.getName()));
			src.delete();
		}
		else if (src.isFile()) {
			trg.getParentFile().mkdirs();
			src.renameTo(trg);
		}
	}

	private File getDirectoryByPropOrEnv(final String name) {
		File file = getDirectoryByProp(name);
		if (file != null) return file;
		return getDirectoryByEnv(name);
	}

	private File getDirectoryByProp(final String name) {
		return _getDirectoryBy(System.getProperty(name));
	}

	private File getDirectoryByEnv(final String name) {
		return _getDirectoryBy(System.getenv(name));
	}

	private File _getDirectoryBy(final String value) {
		if (Util.isEmpty(value, true)) return null;

		final File dir = new File(value);
		dir.mkdirs();
		if (dir.isDirectory()) return dir;

		return null;
	}

	/**
	 * returns the path where the classloader is located
	 *
	 * @param cl ClassLoader
	 * @return file of the classloader root
	 */
	public static File getClassLoaderRoot(final ClassLoader cl) {
		final String path = "lucee/loader/engine/CFMLEngine.class";
		final URL res = cl.getResource(path);
		if (res == null) return null;
		// get file and remove all after !
		String strFile = null;
		try {
			strFile = URLDecoder.decode(res.getFile().trim(), "iso-8859-1");
		}
		catch (final UnsupportedEncodingException e) {

		}
		int index = strFile.indexOf('!');
		if (index != -1) strFile = strFile.substring(0, index);

		// remove path at the end
		index = strFile.lastIndexOf(path);
		if (index != -1) strFile = strFile.substring(0, index);

		// remove "file:" at start and lucee.jar at the end
		if (strFile.startsWith("file:")) strFile = strFile.substring(5);
		if (strFile.endsWith("lucee.jar")) strFile = strFile.substring(0, strFile.length() - 9);

		File file = new File(strFile);
		if (file.isFile()) file = file.getParentFile();

		return file;
	}

	/**
	 * Load CFMl Engine Implementation (lucee.runtime.engine.CFMLEngineImpl) from a Classloader
	 *
	 * @param bundle
	 * @return
	 * @throws ClassNotFoundException
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 */
	private CFMLEngine getEngine(final BundleCollection bc)
			throws ClassNotFoundException, SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException {

		log(Logger.LOG_DEBUG, "state: " + BundleUtil.bundleState(bc.core.getState(), ""));
		// bundle.getBundleContext().getServiceReference(CFMLEngine.class.getName());
		log(Logger.LOG_DEBUG, Constants.FRAMEWORK_BOOTDELEGATION + ":" + bc.getBundleContext().getProperty(Constants.FRAMEWORK_BOOTDELEGATION));
		log(Logger.LOG_DEBUG, "felix.cache.rootdir: " + bc.getBundleContext().getProperty("felix.cache.rootdir"));

		// log(Logger.LOG_DEBUG,bc.master.loadClass(TP.class.getName()).getClassLoader().toString());
		final Class<?> clazz = bc.core.loadClass("lucee.runtime.engine.CFMLEngineImpl");
		log(Logger.LOG_DEBUG, "class:" + clazz.getName());
		final Method m = clazz.getMethod("getInstance", new Class[] { CFMLEngineFactory.class, BundleCollection.class });
		return (CFMLEngine) m.invoke(null, new Object[] { this, bc });

	}

	private class UpdateChecker extends Thread {
		private final CFMLEngineFactory factory;
		private final Identification id;

		private UpdateChecker(final CFMLEngineFactory factory, final Identification id) {
			this.factory = factory;
			this.id = id;
		}

		@Override
		public void run() {
			long time = 10000;
			while (true)
				try {
					sleep(time);
					time = 1000 * 60 * 60 * 24;
					factory._update(id);

				}
				catch (final Exception e) {

				}
		}
	}

	public Logger getLogger() {
		return logger;
	}

}
