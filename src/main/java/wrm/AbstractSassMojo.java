package wrm;

import io.bit3.jsass.CompilationException;
import io.bit3.jsass.Output;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.project.MavenProject;
import wrm.libsass.SassCompiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public abstract class AbstractSassMojo extends AbstractMojo {

	/**
	 * The directory in which the compiled CSS files will be placed. The default value is
	 * <tt>${project.build.directory}</tt>
	 *
	 * @parameter property="project.build.directory"
	 * @required
	 */
	protected File outputPath;
	/**
	 * The directory from which the source .scss files will be read. This directory will be
	 * traversed recursively, and all .scss files found in this directory or subdirectories
	 * will be compiled. The default value is <tt>src/main/sass</tt>
	 *
	 * @parameter default-value="src/main/sass"
	 */
	protected String inputPath;
	/**
	 * Additional include path, ';'-separated. The default value is <tt>null</tt>
	 *
	 * @parameter
	 */
	protected String includePath;
	/**
	 * Output style for the generated css code. One of <tt>nested</tt>, <tt>expanded</tt>,
	 * <tt>compact</tt>, <tt>compressed</tt>. Note that as of libsass 3.1, <tt>expanded</tt>
	 * and <tt>compact</tt> are the same as <tt>nested</tt>. The default value is
	 * <tt>nested</tt>.
	 *
	 * @parameter default-value="nested"
	 */
	private SassCompiler.OutputStyle outputStyle;
	/**
	 * Emit comments in the compiled CSS indicating the corresponding source line. The default
	 * value is <tt>false</tt>
	 *
	 * @parameter default-value="false"
	 */
	private boolean generateSourceComments;
	/**
	 * Generate source map files. The generated source map files will be placed in the directory
	 * specified by <tt>sourceMapOutputPath</tt>. The default value is <tt>true</tt>.
	 *
	 * @parameter default-value="true"
	 */
	private boolean generateSourceMap;
	/**
	 * The directory in which the source map files that correspond to the compiled CSS will be
	 * placed. The default value is <tt>${project.build.directory}</tt>
	 *
	 * @parameter property="project.build.directory"
	 */
	private String sourceMapOutputPath;
	/**
	 * Prevents the generation of the <tt>sourceMappingURL</tt> special comment as the last
	 * line of the compiled CSS. The default value is <tt>false</tt>.
	 *
	 * @parameter default-value="false"
	 */
	private boolean omitSourceMapingURL;
	/**
	 * Embeds the whole source map data directly into the compiled CSS file by transforming
	 * <tt>sourceMappingURL</tt> into a data URI. The default value is <tt>false</tt>.
	 *
	 * @parameter default-value="false"
	 */
	private boolean embedSourceMapInCSS;
	/**
	 * Embeds the contents of the source .scss files in the source map file instead of the
	 * paths to those files. The default value is <tt>false</tt>
	 *
	 * @parameter default-value="false"
	 */
	private boolean embedSourceContentsInSourceMap;
	/**
	 * Switches the input syntax used by the files to either <tt>sass</tt> or <tt>scss</tt>.
	 * The default value is <tt>scss</tt>.
	 *
	 * @parameter default-value="scss"
	 */
	private SassCompiler.InputSyntax inputSyntax;
	/**
	 * Precision for fractional numbers. The default value is <tt>5</tt>.
	 *
	 * @parameter default-value="5"
	 */
	private int precision;
	/**
	 * Enables classpath aware importer which make possible to <tt>@import</tt>
	 * files from classpath and WebJars.
	 *
	 * @parameter default-value="false"
	 */
	private boolean enableClasspathAwareImporter;
	/**
	 * should fail the build in case of compilation errors.
	 *
	 * @parameter default-value="true"
	 */
	protected boolean failOnError;
	
    /**
     * Copy source files to output directory.
     *
     * @parameter default-value="false"
     */
    private boolean copySourceToOutput;
    /**
	 * @parameter property="project"
	 * @required
	 * @readonly
	 */
	protected MavenProject project;
	protected SassCompiler compiler;

	public AbstractSassMojo() {
		super();
	}

	protected void compile() throws Exception {
		final Path root = project.getBasedir().toPath().resolve(Paths.get(inputPath));
		String fileExt = getFileExtension();
		String globPattern = "glob:{**/,}*."+fileExt;
		getLog().debug("Glob = " + globPattern);
	
		final PathMatcher matcher = FileSystems.getDefault().getPathMatcher(globPattern);
		final AtomicInteger errorCount = new AtomicInteger(0);
		final AtomicInteger fileCount = new AtomicInteger(0);
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (matcher.matches(file) && !file.getFileName().toString().startsWith("_")) {
					fileCount.incrementAndGet();
					if(!processFile(root, file)){
						errorCount.incrementAndGet();
					}
				}
	
				return FileVisitResult.CONTINUE;
			}
	
			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
				return FileVisitResult.CONTINUE;
			}
		});
	
		getLog().info("Compiled " + fileCount + " files");
		if (errorCount.get() > 0) {
			if (failOnError) {
				throw new Exception("Failed with " + errorCount.get() + " errors");
			} else {
				getLog().error("Failed with " + errorCount.get() + " errors. Continuing due to failOnError=false.");
			}
		}
	}

	protected String getFileExtension() {
		return inputSyntax.toString();
	}

	protected void validateConfig() {
		setCompileClasspath();
		if (!generateSourceMap) {
			if (embedSourceMapInCSS) {
				getLog().warn("embedSourceMapInCSS=true is ignored. Cause: generateSourceMap=false");
			}
			if (embedSourceContentsInSourceMap) {
				getLog().warn("embedSourceContentsInSourceMap=true is ignored. Cause: generateSourceMap=false");
			}
		}
		if (outputStyle != SassCompiler.OutputStyle.compressed && outputStyle != SassCompiler.OutputStyle.nested) {
			getLog().warn("outputStyle=" + outputStyle + " is replaced by nested. Cause: libsass 3.1 only supports compressed and nested");
		}
	}

	private void setCompileClasspath() {
		try {
			Set<URL> urls = new HashSet<>();
			List<String> elements = project.getCompileClasspathElements();
			for (String element : elements) {
				urls.add(new File(element).toURI().toURL());
			}

			ClassLoader contextClassLoader = URLClassLoader.newInstance(
					urls.toArray(new URL[0]),
					Thread.currentThread().getContextClassLoader());

			Thread.currentThread().setContextClassLoader(contextClassLoader);

		} catch (DependencyResolutionRequiredException e) {
			throw new RuntimeException(e);
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
	}

	protected SassCompiler initCompiler() {
		SassCompiler compiler = new SassCompiler();
		compiler.setEmbedSourceMapInCSS(this.embedSourceMapInCSS);
		compiler.setEmbedSourceContentsInSourceMap(this.embedSourceContentsInSourceMap);
		compiler.setGenerateSourceComments(this.generateSourceComments);
		compiler.setGenerateSourceMap(this.generateSourceMap);
		compiler.setIncludePaths(this.includePath);
		compiler.setInputSyntax(this.inputSyntax);
		compiler.setOmitSourceMappingURL(this.omitSourceMapingURL);
		compiler.setOutputStyle(this.outputStyle);
		compiler.setPrecision(this.precision);
		compiler.setEnableClasspathAwareImporter(this.enableClasspathAwareImporter);
		return compiler;
	}

	protected boolean processFile(Path inputRootPath, Path inputFilePath) throws IOException {
		getLog().debug("Processing File " + inputFilePath);
	
		Path relativeInputPath = inputRootPath.relativize(inputFilePath);
	
		Path outputRootPath = this.outputPath.toPath();
		Path outputFilePath = outputRootPath.resolve(relativeInputPath);
		String fileExtension = getFileExtension();
		outputFilePath = Paths.get(outputFilePath.toAbsolutePath().toString().replaceFirst("\\."+fileExtension+"$", ".css"));
	
		Path sourceMapRootPath = Paths.get(this.sourceMapOutputPath);
		Path sourceMapOutputPath = sourceMapRootPath.resolve(relativeInputPath);
		sourceMapOutputPath = Paths.get(sourceMapOutputPath.toAbsolutePath().toString().replaceFirst("\\.scss$", ".css.map"));
	
		if (copySourceToOutput) {
			Path inputOutputPath = outputRootPath.resolve(relativeInputPath);
			inputOutputPath.toFile().mkdirs();
			Files.copy(inputFilePath, inputOutputPath, REPLACE_EXISTING);
			inputFilePath = inputOutputPath;
		}
		
		
		Output out;
		try {
			out = compiler.compileFile(
					inputFilePath.toAbsolutePath().toString(),
					outputFilePath.toAbsolutePath().toString(),
					sourceMapOutputPath.toAbsolutePath().toString()
			);
		}
		catch (CompilationException e) {
			getLog().error(e.getMessage());
			getLog().debug(e);
			return false;
		}
	
		getLog().debug("Compilation finished.");
	
		writeContentToFile(outputFilePath, out.getCss());
		if (out.getSourceMap() != null) {
			writeContentToFile(sourceMapOutputPath, out.getSourceMap());
		}
		return true;
	}

	private void writeContentToFile(Path outputFilePath, String content) throws IOException {
		File f = outputFilePath.toFile();
		f.getParentFile().mkdirs();
		f.createNewFile();
		OutputStreamWriter os = null;
		try{
			os = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
			os.write(content);
			os.flush();
		} finally {
			if (os != null)
				os.close();
		}
		getLog().debug("Written to: " + f);
	}

	
}