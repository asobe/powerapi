/**
 * Copyright (C) 2012 Inria, University Lille 1.
 *
 * This file is part of PowerAPI.
 *
 * PowerAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * PowerAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PowerAPI. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contact: powerapi-user-list@googlegroups.com.
 */
package fr.inria.powerapi.sensor.libpfm;

import perfmon2.libpfm.LibpfmLibrary;
import perfmon2.libpfm.perf_event_attr;

import org.bridj.BridJ;
import org.bridj.CRuntime;
import org.bridj.Pointer;
import org.bridj.ann.Library;
import org.bridj.ann.Ptr;
import org.bridj.ann.Runtime;
import org.bridj.ann.CLong;

/**
 * This class is used to bind some useful functions available natively in the C language.
 */
@Library("c") 
@Runtime(CRuntime.class) 
public class CUtils {
    static {
        BridJ.register();
    }

    /**
     * Represents the perf_event_open maccro, which is not correctly generated by jnaerator.
     */
    public static int perf_event_open(Pointer<perf_event_attr> __hw, int __pid, int __cpu, int __gr, @CLong long __flags) {
        return syscall(LibpfmLibrary.__NR_perf_event_open, Pointer.getPeer(__hw), __pid, __cpu, __gr, __flags);
    }
    private native static int syscall(int __cdde, Object... varArgs1);

    /**
     * Used to interact with a given file descriptor. In this case, we use it to enable, disable and reset a file descriptor (so, a counter).
     */
    public static native int ioctl(int __fd, @CLong long __request, Object... varArgs1);

    /**
     * Allows to read the values from a file descriptor.
     */
    public static long read(int __fd, Pointer<? > __buf, @CLong long __nbytes) {
        return read(__fd, Pointer.getPeer(__buf), __nbytes);
    }
    private native static long read(int __fd, @CLong long __buf, @CLong long __nbytes);

    /**
     * Closes a file descriptor
     */
    public static native int close(int __fd);
}