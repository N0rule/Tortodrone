CLASSES := build/.build/classes
TEST_CLASSES := build/.build/test-classes
SERVER_JAR := build/tortodrone.jar
MAIN_CLASS := org.tortodrone.server.Server
SOURCE_DIR := src/main/java
RESOURCE_DIR := src/main/resources
TEST_SOURCE_DIR := src/test/java
SOURCES := $(sort $(shell find $(SOURCE_DIR) -name '*.java'))
RESOURCES := $(sort $(shell find $(RESOURCE_DIR) -type f))
TEST_SOURCES := $(sort $(shell find $(TEST_SOURCE_DIR) -name '*.java'))
UNIT_TEST_SOURCES := $(filter-out $(TEST_SOURCE_DIR)/org/tortodrone/integration/%, $(filter %Test.java,$(TEST_SOURCES)))
UNIT_TESTS := $(subst /,.,$(patsubst $(TEST_SOURCE_DIR)/%.java,%,$(UNIT_TEST_SOURCES)))

.PHONY: build run server test test-unit test-integration test-compile clean
build: $(SERVER_JAR)

$(SERVER_JAR): $(SOURCES) $(RESOURCES) VERSION Makefile
	rm -rf $(CLASSES)
	mkdir -p $(CLASSES) build
	javac --release 17 -d $(CLASSES) $(SOURCES)
	cp VERSION $(CLASSES)/version.txt
	jar --create --file $(SERVER_JAR) --main-class $(MAIN_CLASS) -C $(CLASSES) . -C $(RESOURCE_DIR) .

run server: build
	cd build && java -jar $(notdir $(SERVER_JAR))

test: test-unit test-integration

test-compile: build
	rm -rf $(TEST_CLASSES)
	mkdir -p $(TEST_CLASSES)
	javac --release 17 -cp $(SERVER_JAR) -d $(TEST_CLASSES) $(TEST_SOURCES)

test-unit: test-compile
	@set -e; for suite in $(UNIT_TESTS); do \
		java -cp $(SERVER_JAR):$(TEST_CLASSES) "$$suite"; \
	done

test-integration: test-compile
	java -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.integration.ServerIntegrationTest $(SERVER_JAR)

clean:
	rm -rf build/resources $(SERVER_JAR) build/tortodrone-server.jar build/.build .build
