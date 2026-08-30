FROM centos:7

RUN sed -i 's|^mirrorlist=|#mirrorlist=|g' /etc/yum.repos.d/CentOS-*.repo && \
    sed -i 's|^#[ ]*baseurl=http://mirror.centos.org|baseurl=https://vault.centos.org|g' /etc/yum.repos.d/CentOS-*.repo && \
    rpm --import /etc/pki/rpm-gpg/RPM-GPG-KEY-CentOS-7 && \
    yum install -y epel-release && \
    yum install -y gcc cmake3 make git java-1.8.0-openjdk-devel && \
    yum clean all

ENV JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk

ARG UNICORN_COMMIT=unknown
ARG UNICORN_REPO=https://github.com/zhkl0228/unicorn
ARG UNICORN_BRANCH=unicorn2
# unibase trace2 分支(uc_trace): --build-arg UNICORN_REPO=https://github.com/memoriaru/unicorn --build-arg UNICORN_BRANCH=unibase/trace2
RUN git clone --depth 1 -b ${UNICORN_BRANCH} ${UNICORN_REPO} /build/unicorn

WORKDIR /build/unicorn
RUN mkdir build && cd build && \
    cmake3 .. -DCMAKE_BUILD_TYPE=Release \
              -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
              -DUNICORN_ARCH="arm;aarch64" && \
    make -j$(nproc) VERBOSE=1

WORKDIR /build/jni
COPY *.c *.h CMakeLists.txt ./

RUN mkdir build && cd build && \
    cmake3 .. -DUNICORN_HOME=/build/unicorn && \
    make VERBOSE=1

CMD ["cp", "/build/jni/build/libunicorn.so", "/output/libunicorn.so"]
