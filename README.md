GSRS Visualization Data Generator for FDA VIP by Conceptant, Inc
============================
Generates JSON files in a format suitable for GSRS connections graph visualization. 

### Requires:
1. JDK 1.8+
2. maven

### Usage
To build the jar, first make sure that your JAVA_HOME variable points to the location of the jdk, then run
```
mvn clean package
```

This will produce an executable jar file in the directory called "target". The name of the file will look like this:
```
gsrsnetworkmaker-0.0.1-SNAPSHOT-jar-with-dependencies.jar
```

Run the .jar with "-h" flag to find more about its arguments:
```
java -jar gsrsnetworkmaker-0.0.1-SNAPSHOT-jar-with-dependencies.jar -h
```

Example of how to run the app with all parameters:
```
java -jar gsrsnetworkmaker-0.0.1-SNAPSHOT-jar-with-dependencies.jar -f /tmp/dump-public-2020-10-01.gsrs -d /tmp/gsrsJsons -l 2 -m 100 
```

##### NOTES
If you do not want to see all the logs substitute "DEBUG" with "ERROR" in file "src/main/resources/log4j.properties".
