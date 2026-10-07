JAVA := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)
JAVAC := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/javac,javac)
JAR := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/jar,jar)
CLASSES := .build/classes
TEST_CLASSES := .build/test-classes
SERVER_JAR := build/tortodrone-server.jar
SOURCES := $(shell find src/main/java -name '*.java')
TEST_SOURCES := $(shell find src/test/java -name '*.java')

.PHONY: build run server test clean
build: $(SERVER_JAR)

$(SERVER_JAR): $(SOURCES) server.properties.example Makefile
	rm -rf $(CLASSES)
	mkdir -p $(CLASSES) build/resources
	"$(JAVAC)" --release 17 -d $(CLASSES) $(SOURCES)
	cp server.properties.example build/resources/server.properties
	"$(JAR)" --create --file $(SERVER_JAR) --main-class org.tortodrone.server.Server -C $(CLASSES) . -C build/resources server.properties

run server: build
	"$(JAVA)" -jar $(SERVER_JAR)

test: build
	mkdir -p $(TEST_CLASSES)
	"$(JAVAC)" --release 17 -cp $(SERVER_JAR) -d $(TEST_CLASSES) $(TEST_SOURCES)
	"$(JAVA)" -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.server.HandshakeTest
	"$(JAVA)" -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.server.BootstrapTest
	"$(JAVA)" -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.server.SessionTest
	"$(JAVA)" -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.server.LocalAuthenticationTest
	"$(JAVA)" -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.server.CharacterStoreTest
	"$(JAVA)" -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.server.CharacterCreationTest
	"$(JAVA)" -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.server.ServerIntegrationTest $(SERVER_JAR)

clean:
	rm -rf build/resources build/tortodrone-server.jar .build
