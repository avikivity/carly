FROM scylla_latest
MAINTAINER Yoav Kleinberger <yoav@scylladb.com>

USER root
# enable systemd
ENV container docker
VOLUME [ "/sys/fs/cgroup" ]
CMD ["/usr/sbin/init"]

# ssh
RUN mkdir -p /root/.ssh
RUN chmod 700 /root/.ssh
RUN touch /root/.ssh/authorized_keys
RUN chmod 600 /root/.ssh/authorized_keys
RUN yum -y install openssh-server iproute sudo
RUN systemctl enable sshd
EXPOSE 22

# fix sudoers
RUN sed -i -e 's/Defaults.*requiretty//' /etc/sudoers 
