# seed

seed is designed to sync a remote host's directory with a local directory based on some filters.

For example: syncing all `.flac` and `.mp3` files on a remote host to a local `~/music` dir and all `.mkv` files to a `~/movies` dir.

After files have been transferred to a local directory, arbitrary hooks can also be configured.

seed is configured using EDN.

## Getting started

```bash
git clone ...
clojure -m seed.core /path/to/config.edn
```

## Example configuration

```clojure
{:sftp/pool                    {:host        "xxx.foo.cz"
                                :username    "foo"
                                :known-hosts "~/known_hosts"
                                :password    "zzzzzz"
                                :n-conns     20}

 :sftp/source                  {:pool    #ig/ref :sftp/pool
                                :dir     "/media/files"
                                :cache   "cache.edn"
                                :poll-ms 60000
                                :depth   3
                                :targets #ig/refset :sftp/target}

 [:source/music :sftp/target]  {:dir     "/Media/music"
                                :filters [[:some-ext #{".flac" ".mp3"}]]}

 [:source/movies :sftp/target] {:dir     "/Media/movies"
                                :filters [[:some-ext #{".mkv"}]]}}
```

Read through the next section for an explanation of the available components.

## Components

Configuration is defined as an EDN file, which is just an [integrant config map](https://github.com/weavejester/integrant)

This section documents the available integrant components and ways they can be [composed](https://github.com/weavejester/integrant#composite-keys) together

### :sftp/client

Defines a `com.jcraft.jsch.JSch` instance:

* `username` - username for session
* `password` - password for session
* `port` - port for connection (default : 22)
* `host` - the host for connection
* `known-hosts` - path to known_hosts file, default `~/.ssh/known_hosts`

### :sftp/source

A source polls for new files from a remote host at a specified interval:

* `client` - a reference to the `:sftp/client` component
* `dir` - the remote directory to poll for files
* `depth` - the depth in which to scan for files (default: 1)
* `poll-ms` - how often in milliseconds to poll for new files
* `targets` -  a collection of `:sftp/target`
* `cache` - the location of the cache file to be generated (default: `cache.edn`)

### :sftp/target

A target is a local directory, and the destination for new files to be copied from a source.

A target contains a collection of filters, which can 

## License

Copyright © 2021 FIXME

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
