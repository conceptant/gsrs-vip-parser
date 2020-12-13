package gov.hhs.fda.ohi.gsrsnetworkmaker;

import java.io.File;

public class Args {
    public File gsrsFile;
    public File outputDirectory;
    public Integer nestingLevel;
    public Integer maxNumberOfElements;
    public Integer maxNumberOfLinksPerNode;

    public Args(File gsrsFile, File outputDirectory, Integer nestingLevel, Integer maxNumberOfElements, Integer maxNumberOfLinksPerNode) {
        this.gsrsFile = gsrsFile;
        this.outputDirectory = outputDirectory;
        this.nestingLevel = nestingLevel;
        this.maxNumberOfElements = maxNumberOfElements;
        this.maxNumberOfLinksPerNode = maxNumberOfLinksPerNode;
    }
}
