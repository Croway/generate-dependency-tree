///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.apache.maven.shared:maven-invoker:3.2.0
//DEPS org.apache.maven:maven-model:3.8.6
//DEPS org.apache.maven:maven-settings:3.8.6

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

@Command(name = "StarterAnalysis", mixinStandardHelpOptions = true, version = "StarterAnalysis 0.1",
        description = "StarterAnalysis made with jbang")
class StarterAnalysis implements Callable<Integer> {

    @Parameters(index = "0", description = "pom.xml location", defaultValue = "World!")
    private String pomLocation;

    @Option(names = {"-sb-version"}, description = "Spring Boot version")
    private String springBootVersion;

    @Option(names = {"-csb-version"}, description = "Camel Spring Boot version")
    private String camelSpringBootVersion;

    @Option(names = {"-output"}, description = "Dependency Tree file name")
    private String depTreeFileName;
        
    @Option(names = {"-maven-home"}, description = "Maven home")
    private String mavenHome;

    public static void main(String... args) {
        int exitCode = new CommandLine(new StarterAnalysis()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        System.setProperty("maven.home", mavenHome);
            
        Model pom = loadPom(Paths.get(pomLocation));

        Model result = new Model();
        
        DependencyManagement dependencyManagement = new DependencyManagement();
        Dependency springBootDependency = new Dependency();
        springBootDependency.setGroupId("org.springframework.boot");
        springBootDependency.setArtifactId("spring-boot-dependencies");
        springBootDependency.setVersion(springBootVersion);
        springBootDependency.setType("pom");
        springBootDependency.setScope("import");
        Dependency camelSpringBootDependency = new Dependency();
        camelSpringBootDependency.setGroupId("org.apache.camel.springboot");
        camelSpringBootDependency.setArtifactId("camel-spring-boot-bom");
        camelSpringBootDependency.setVersion(camelSpringBootVersion);
        camelSpringBootDependency.setType("pom");
        camelSpringBootDependency.setScope("import");

        dependencyManagement.addDependency(springBootDependency);
        dependencyManagement.addDependency(camelSpringBootDependency);

        result.setDependencyManagement(dependencyManagement);
        result.setModelVersion("4.0.0");

        Map<String, List<String>> map = new HashMap<>();
        Invoker invoker = new DefaultInvoker();
        pom.getDependencies().parallelStream()
        .forEach(dependency -> {
            System.out.println("Building dependency " + dependency.getGroupId() + ":" + dependency.getArtifactId());

            List<String> output = new ArrayList<>(500);

            result.setGroupId("test." + dependency.getGroupId());
            result.setArtifactId("test." + dependency.getArtifactId());
            result.setVersion("1.0.0-SNAPSHOT");

            result.addDependency(dependency);

            try (OutputStream os = new FileOutputStream(dependency.getArtifactId() + ".xml")) {
                new MavenXpp3Writer().write(os, result);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }

            InvocationRequest request = new DefaultInvocationRequest();
            request.setPomFile(new File(dependency.getArtifactId() + ".xml"));
            request.setGoals(Collections.singletonList( "dependency:tree" ));
            request.setOutputHandler(new ListCustomHandler(output));    
            request.setInputStream(InputStream.nullInputStream());
            
            try {
                invoker.execute(request);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }

            map.put(dependency.getGroupId() + ":" + dependency.getArtifactId(), output);
            result.removeDependency(dependency);
        });

        map.entrySet().forEach(entry -> {
            try {
                Files.write(Paths.get(depTreeFileName), entry.getValue(), Charset.defaultCharset(), StandardOpenOption.APPEND);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        System.out.print(depTreeFileName + " DONE");

        return 0;
    }
    
    public static Model loadPom(Path pom) {
        Model model;
        try (InputStream is = new FileInputStream(pom.toFile())) {
            model = new MavenXpp3Reader().read(is);
        } catch (IOException | XmlPullParserException e) {
            throw new RuntimeException("Unable to load POM " + pom.toAbsolutePath(), e);
        }
        return model;
    }

    class ListCustomHandler implements InvocationOutputHandler {

        private final List<String> list;

        public ListCustomHandler(List<String> list) {
            this.list = list;
        }

        @Override
        public void consumeLine(String line) throws IOException {
            list.add(line);
        }

    }
}
