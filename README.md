# seed

seed is designed to sync a remote host's directory with a local directory based on some filters.

For example: syncing all `.flac` and `.mp3` files on a remote host to a local `~/music` dir and all `.mkv` files to a `~/movies` dir.

After files have been transferred to a local directory, arbitrary hooks can also be configured.

seed is configured using EDN.

seed currently only supports the sFTP protocol for sources.

## Getting started

```bash
git clone git@github.com:wavejumper/seed.git
lein uberjar
java -jar target/seed.jar config.edn
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

### :sftp/pool

Defines a `com.jcraft.jsch.JSch` connection pool:

* `username` - username for session
* `password` - password for session
* `port` - port for connection (default : 22)
* `host` - the host for connection
* `known-hosts` - path to known_hosts file, default `~/.ssh/known_hosts`
* `n-conns` - the number of connections in the pool (default: 5)

#### Notes on known-hosts

JSch prefers a known_hosts file in the RSA format. 

If JSch fails to read `~/.ssh/known-hosts`, you can create a known_hosts file using the RSA format like so:

```bash 
ssh-keyscan -H -t rsa example.org >> known_hosts
```

### :sftp/source

A source polls for new files from a remote host at a specified interval:

* `client` - a reference to the `:sftp/client` component
* `dir` - the remote directory to poll for files
* `depth` - the depth in which to scan for files (default: 1)
* `poll-ms` - how often in milliseconds to poll for new files
* `targets` -  a collection of `:sftp/target`
* `cache` - the location of the cache file to be generated (default: `cache.edn`)

### :sftp/target

A target is a local directory that is the destination for new files to be copied from a source.

A target contains a collection of filters. If a new file from the source matches every filter, it will be copied to the target destination.

* `dir` - the local directory
* `filters` - a collection of filters. A filter is a tuple of `[:id & args]`

#### Custom filters

You can add your own custom filters by extending the `seed.core/filter-fn` multimethod.

For example, `:some-ext` is implemented as:

```clojure 
(defmethod filter-fn :some-ext
  [[_ exts]]
  (fn [{:keys [filename]}]
    (some #(str/ends-with? filename %) exts)))
```

## License

Copyright © 2021

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
