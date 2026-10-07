JAVA := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)
JAVAC := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/javac,javac)
MAIN := org.tortodrone.server.ConnectionLogger
CLASSES := build/classes

.PHONY: build run test
build:
	mkdir -p $(CLASSES)
	"$(JAVAC)" --release 17 -d $(CLASSES) server/src/main/java/org/tortodrone/server/ConnectionLogger.java

run: build
	"$(JAVA)" -cp $(CLASSES) $(MAIN)

test: build
	python3 tools/test_listener.py "$(JAVA)"
