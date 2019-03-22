# OpenNMS-Mib2Conf

A command line tool to compile MIBs using [Mibble](https://www.mibble.org) to generate Event definitions based on SNMP Traps, or Data Collection Group files based on scalar and tabular metrics found on the MIB.

> WARNING: This is still under development.

* [Oracle JDK](https://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) version 8
* [Gradle](https://gradle.org/install/) version 5.x

# Compilation

```shell
gradle fatJar
```

The generated JAR with dependencies should be available on the `./build/lib/` directory.

As latest JARs from `Mibble` are not available on Maven central. The `./lib` directory contains those JARs and are packaged with the rest of the dependencies on a single JAR to execute the CLI compiler.

# Usage

```shell
$ java -jar build/libs/mib2conf-all-1.0.jar -h
Usage: mib2conf [-hV] -m=mib [-t=target]
  -h, --help            Show this help message and exit.
  -m, --mibFile=mib     Path to the MIB file
                        Dependencies should be on the same directory
  -t, --target=target   Target Configuration: events, dataCollection
                        Default: events
  -V, --version         Print version information and exit.
```

The `mibs` directory contains multiple core MIBs and several sample MIBs to help you compile your own. The idea is copy your new MIB there, and try to compile it. If more dependencies are needed, find and download those dependencies, and copy them to that folder as well.

# Future Enhancements

* Implement a name cutter for the MibObj aliases. For this, do it after generating the configuration, in order to have a common preffix for all the aliases on the same group, and then cut the names based on a common prefix to make the aliases more readable, which is the problem with the current implementation.
