all: scylladb-jepsen carly-check
HOME_BIN=~/bin
LEIN=$(HOME_BIN)/lein

from_scratch: scylladb-jepsen carly-check scylla-tools-java generate-keys

home_bin:
	mkdir -p $(HOME_BIN)

leiningen: home_bin
	[[ -x $(LEIN) ]] || wget -O $(HOME_BIN)/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
	chmod +x $(LEIN)
	$(LEIN) version

scylladb-jepsen: leiningen
	git clone https://github.com/scylladb/jepsen --branch carly_oriented ~/scylladb-jepsen
	cd ~/scylladb-jepsen/jepsen ; $(LEIN) install

install-docker:
	which docker || curl -fsSL https://get.docker.com/ | sh
	sudo usermod -aG docker $(USER)
	sudo systemctl start docker
	@echo YOU MUST LOGIN AGAIN NOW SO THAT YOU CAN USE DOCKER

carly-dependencies:
	cd carly ; $(LEIN) deps

scylla-tools-java:
	cd ~ ; git clone https://github.com/scylladb/scylla-tools-java.git
	cd ~/scylla-tools-java ; ant

generate-keys:
	cd carly ; rm -f public_key_rsa private_key_rsa
	cd carly ; ssh-keygen -f jepsen_key -N ''
	cd carly ; mv jepsen_key private_key_rsa
	cd carly ; mv jepsen_key.pub public_key_rsa

carly-check: carly-dependencies
