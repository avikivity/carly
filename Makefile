all: scylladb-jepsen carly-check
HOME_BIN=~/bin
LEIN=$(HOME_BIN)/lein

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

carly-check: carly-dependencies
