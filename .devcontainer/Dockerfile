FROM mingc/android-build-box:latest

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH=$PATH:/usr/local/go/bin:/root/go/bin

RUN wget -q https://go.dev/dl/go1.26.3.linux-amd64.tar.gz \
    && tar -C /usr/local -xzf go1.26.3.linux-amd64.tar.gz \
    && rm go1.26.3.linux-amd64.tar.gz

RUN JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 sdkmanager "ndk;27.2.12479018"

ENV ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018

RUN go install golang.org/x/mobile/cmd/gomobile@latest \
    && gomobile init

WORKDIR /workspace
