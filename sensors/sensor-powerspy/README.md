# PowerSpy PowerAPI Sensor

## Presentation

PowerAPI `Sensor` providing power metrics from a [PowerSpy](http://www.alciom.com/fr/produits/powerspy2.html "PowerSpy") outlet under a Linux platform. 

## In

This module reacts to `Tick` messages, typically sent by the core `Clock` class.

## Out

`PowerSpySensorMessage(currentRMS: Double, uScale: Float, iScale: Float, tick: Tick)` message which:
* `currentRMS` is the square of the current RMS (Root Mean Square, see documentation) ;
* `uScale` is the factory correction voltage coefficient ;
* `iScale` is the factory correction amperage coefficient ;
* `tick` is the nearest current `Tick`. Nearest because we cannot perfectly synchronize the PowerSpy with PowerAPI. So when a new value comes from the PowerSpy, the nearest PowerAPI's `Tick` value is associated to it.

## Configuration

This module has to know adress of the given [PowerSpy](http://www.alciom.com/fr/produits/powerspy2.html "PowerSpy"), represented by a BtSPP (Bluetooth Serial Port Protocol) URL.
He has to know also the PowerSpy version.

For example:
```
powerapi {
	sensor {
		powerspy {
			spp-url = "btspp://the-powerspy-mac-address;some=value"
			version = 1
		}
	}
}
```

To establish the connection between PowerSPY and PowerAPI, one library is required (bluecove). The easiest way is to install this library:
* `libbluetooth-dev` (not required if PowerSPY worked before)

If you don't have GUI and use PowerSPY v1, please follow these steps to pair-up PowerAPI with PowerSPY:
* hcitool dev (get the mac address of your bluetooth card or adapter)
* hcitool scan (get the mac address of your PowerSPY)
* sudo hciconfig hci0 piscan
* Check the file /var/lib/bluetooth/${bluetooth_mac_adress}/pincodes (please, create the file it it doesn't exist)
* Add the following line: ${powersy_mac_adress} 0000
* sudo /etc/init.d/bluetooth restart (or restart bluetooth)