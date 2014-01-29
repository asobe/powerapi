# PowerAPI virtio reporter

Write the energy consumption using `VirtioSerial`(unix library as a replacement of socket for performances issues)
## In

Listen to `ProcessedMessage`, which are typically provided by `fr.inria.powerapi.processor` module.

## Out

Write inside a file available on the filesystem (in /tmp folder) to send the host consumption to a VM.

## Configuration part

This module has to know the folder path used by VirtioSerial (defined as /tmp/). Also, a `VirtioReporter` can be used with many VMs. The fact remains that, each VM as an available port to communicate. So, one file with the mapping between VM PIDs and ports is needed. See `tool-PowerAPI` for this part.

Be careful, this module as a dependance to junixsocket. The installation is required (see `https://code.google.com/p/junixsocket/`).

For example:
```
powerapi {
	cpu {
		virtio {
			host = "/tmp/"
		}
	}
}
```
