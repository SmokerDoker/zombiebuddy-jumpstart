NAME=zbNative
ARCH=x86_64

CC=$(ARCH)-w64-mingw32-gcc
CFLAGS=-Wall -Os
LDFLAGS=-shared -Wl,--gc-sections -Wl,--strip-all -nostdlib -Wl,--entry=DllMain

TARGET1=$(NAME).dll
SOURCES1=$(NAME).o
OBJECTS1=$(SOURCES1:.c=.o)
DEF1=$(NAME).def

all: $(TARGET1)

# Jumpstart: the same loader for Jumpstart.jar (the fork with Jumpstart built in)
jsNative.dll: $(NAME).c jsNative.def
	$(CC) $(CFLAGS) -DZB_JAR=\"Jumpstart.jar\" -c $(NAME).c -o jsNative.o
	$(CC) $(LDFLAGS) -o $@ jsNative.o jsNative.def -lkernel32 -luser32

$(TARGET1): $(OBJECTS1)
	$(CC) $(LDFLAGS) -o $@ $^ $(DEF1) -lkernel32 -luser32

%.o: %.c
	$(CC) $(CFLAGS) -c $<

clean:
	rm -f $(OBJECTS1) $(TARGET1) jsNative.o jsNative.dll
