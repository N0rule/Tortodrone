JAVA := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)
JAVAC := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/javac,javac)
JAR := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/jar,jar)
CLASSES := build/classes
SERVER_JAR := build/tortodrone-server.jar
SOURCES := $(shell find server/src/main/java protocol/src/main/java -name '*.java')
TEST_SOURCES := $(shell find server/src/test/java -name '*.java')

.PHONY: build run server test clean
build: $(SERVER_JAR)

$(SERVER_JAR): $(SOURCES) server.properties.example Makefile
	rm -rf $(CLASSES)
	mkdir -p $(CLASSES)
	"$(JAVAC)" --release 17 -d $(CLASSES) $(SOURCES)
	mkdir -p build/resources
	cp server.properties.example build/resources/server.properties
	"$(JAR)" --create --file $(SERVER_JAR) --main-class org.tortodrone.server.Server -C $(CLASSES) . -C build/resources server.properties

run server: build
	"$(JAVA)" -jar $(SERVER_JAR)

test: build
	mkdir -p build/test-classes
	"$(JAVAC)" --release 17 -cp $(SERVER_JAR) -d build/test-classes $(TEST_SOURCES)
	"$(JAVA)" -cp $(SERVER_JAR):build/test-classes org.tortodrone.server.HandshakeTest
	"$(JAVA)" -cp $(SERVER_JAR):build/test-classes org.tortodrone.server.BootstrapTest
	"$(JAVA)" -cp $(SERVER_JAR):build/test-classes org.tortodrone.server.SessionTest
	"$(JAVA)" -cp $(SERVER_JAR):build/test-classes org.tortodrone.server.LocalAuthenticationTest
	"$(JAVA)" -cp $(SERVER_JAR):build/test-classes org.tortodrone.server.CharacterStoreTest
	"$(JAVA)" -cp $(SERVER_JAR):build/test-classes org.tortodrone.server.CharacterCreationTest
	"$(JAVA)" -cp $(SERVER_JAR):build/test-classes org.tortodrone.server.ServerIntegrationTest $(SERVER_JAR)

clean:
	rm -rf build
