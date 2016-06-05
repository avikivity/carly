FROM centos:7
MAINTAINER Yoav Kleinberger <yoav@scylladb.com>

# enable systemd
ENV container docker
VOLUME [ "/sys/fs/cgroup" ]
CMD ["/usr/sbin/init"]

# ssh
RUN mkdir -p /root/.ssh
RUN chmod 700 /root/.ssh
ADD public_key_rsa /root/.ssh/authorized_keys
RUN chmod 600 /root/.ssh/authorized_keys
RUN yum -y install openssh-server iproute sudo
RUN systemctl enable sshd
EXPOSE 22

# scylla ports
EXPOSE 10000 9042 9160 7000 7001 7199
