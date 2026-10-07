CLASSES := build/.build/classes
TEST_CLASSES := build/.build/test-classes
PACKAGE := build/.build/package
SQLITE := build/deps/sqlite-jdbc-3.53.4.0.jar
SERVER_JAR := build/tortodrone.jar
ADMIN_JAR := build/tortodrone-admin.jar
SOURCES := $(sort $(shell find src/main/java -name '*.java'))
RESOURCES := $(sort $(shell find src/main/resources -type f))
TEST_SOURCES := $(sort $(shell find src/test/java -name '*.java'))
UNIT_TEST_SOURCES := $(filter-out src/test/java/org/tortodrone/integration/%, $(filter %Test.java,$(TEST_SOURCES)))
UNIT_TESTS := $(subst /,.,$(patsubst src/test/java/%.java,%,$(UNIT_TEST_SOURCES)))

.PHONY: build build-server build-admin run server admin test test-unit test-integration test-compile clean
build: build-server build-admin
build-server: $(SERVER_JAR)
build-admin: $(ADMIN_JAR)

$(SQLITE): scripts/fetch-sqlite.sh
	sh scripts/fetch-sqlite.sh $@

build/.build/package.stamp: $(SOURCES) $(RESOURCES) VERSION Makefile $(SQLITE)
	rm -rf $(CLASSES) $(PACKAGE)
	mkdir -p $(CLASSES) $(PACKAGE)
	javac --release 17 -d $(CLASSES) $(SOURCES)
	cp VERSION $(CLASSES)/version.txt
	cp -R $(CLASSES)/. $(PACKAGE)/
	cp -R src/main/resources/. $(PACKAGE)/
	cd $(PACKAGE) && jar --extract --file ../../deps/sqlite-jdbc-3.53.4.0.jar
	rm -f $(PACKAGE)/META-INF/MANIFEST.MF $(PACKAGE)/module-info.class
	printf 'Enable-Native-Access: ALL-UNNAMED\n' > build/.build/native-access.mf
	touch $@

$(SERVER_JAR): build/.build/package.stamp
	jar --create --file $@ --main-class org.tortodrone.server.Server --manifest build/.build/native-access.mf -C $(PACKAGE) .

$(ADMIN_JAR): build/.build/package.stamp
	jar --create --file $@ --main-class org.tortodrone.admin.AdminApplication --manifest build/.build/native-access.mf -C $(PACKAGE) .

run server: build-server
	cd build && java -jar $(notdir $(SERVER_JAR))

admin: build-admin
	java -jar $(ADMIN_JAR)

test: test-unit test-integration

test-compile: build
	rm -rf $(TEST_CLASSES)
	mkdir -p $(TEST_CLASSES)
	javac --release 17 -cp $(SERVER_JAR) -d $(TEST_CLASSES) $(TEST_SOURCES)

test-unit: test-compile
	@set -e; for suite in $(UNIT_TESTS); do \
		java --enable-native-access=ALL-UNNAMED -cp $(SERVER_JAR):$(TEST_CLASSES) "$$suite"; \
	done

test-integration: test-compile
	java --enable-native-access=ALL-UNNAMED -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.integration.ServerIntegrationTest $(SERVER_JAR)
	java --enable-native-access=ALL-UNNAMED -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.integration.AdminIntegrationTest $(SERVER_JAR) $(ADMIN_JAR)
	java --enable-native-access=ALL-UNNAMED -cp $(SERVER_JAR):$(TEST_CLASSES) org.tortodrone.integration.ConsoleIntegrationTest $(SERVER_JAR)

clean:
	rm -rf $(SERVER_JAR) $(ADMIN_JAR) build/tortodrone-server.jar build/.build .build
