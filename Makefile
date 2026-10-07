JAVA := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)
JAVAC := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/javac,javac)
MAIN := org.tortodrone.server.ConnectionLogger
CLASSES := build/classes

.PHONY: build run test
build:
	mkdir -p $(CLASSES)
	"$(JAVAC)" --release 17 -d $(CLASSES) $$(find server/src/main/java protocol/src/main/java -name '*.java')

run: build
	"$(JAVA)" -cp $(CLASSES) $(MAIN)

test: build
	python3 tools/test_listener.py "$(JAVA)"
	"$(JAVAC)" --release 17 -cp $(CLASSES) -d build/test-classes $$(find server/src/test/java -name '*.java')
	"$(JAVA)" -cp $(CLASSES):build/test-classes org.tortodrone.server.HandshakeTest
	"$(JAVA)" -cp $(CLASSES):build/test-classes org.tortodrone.server.LocalAuthenticationTest
