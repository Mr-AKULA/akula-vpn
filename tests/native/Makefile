HEV := ../../androidApp/src/main/jni/hev-socks5-tunnel
TASK := $(HEV)/third-part/hev-task-system
CC = clang
SANITIZERS ?= undefined
BUILD_DIR ?= ../../build/native-tests/$(SANITIZERS)

CFLAGS := -std=gnu11 -g -O1 -Wall -Wextra -Wno-unused-parameter \
	-fsanitize=$(SANITIZERS) -fno-sanitize-recover=all -fno-omit-frame-pointer
CPPFLAGS := -I$(HEV)/src -I$(HEV)/src/misc -I$(TASK)/include -I$(TASK)/src \
	-I$(HEV)/src/core/src
SOURCES := mapped-dns-restart.c $(HEV)/src/hev-mapped-dns.c \
	$(HEV)/src/misc/hev-logger.c \
	$(TASK)/src/lib/object/hev-object.c \
	$(HEV)/src/misc/hev-list.c \
	$(HEV)/src/core/src/hev-rbtree.c

.PHONY: test
test: $(BUILD_DIR)/mapped-dns-restart
	$(BUILD_DIR)/mapped-dns-restart

$(BUILD_DIR)/mapped-dns-restart: $(SOURCES) $(HEV)/src/hev-mapped-dns.h Makefile
	mkdir -p $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) $(SOURCES) -pthread -o $@
