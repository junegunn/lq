all:
	lein uberjar

repl:
	lein ring server-headless

.PHONY: all repl
