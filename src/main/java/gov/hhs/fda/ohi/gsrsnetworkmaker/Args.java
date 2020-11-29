package gov.hhs.fda.ohi.gsrsnetworkmaker;

import java.io.File;

public class Args {
    public File gsrsFile;
    public File outputDirectory;
    public Integer nestingLevel;
    public Integer maxNumberOfElements;

    public Args(File gsrsFile, File outputDirectory, Integer nestingLevel, Integer maxNumberOfElements) {
        this.gsrsFile = gsrsFile;
        this.outputDirectory = outputDirectory;
        this.nestingLevel = nestingLevel;
        this.maxNumberOfElements = maxNumberOfElements;
    }
}
