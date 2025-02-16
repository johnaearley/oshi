/**
 * OSHI (https://github.com/oshi/oshi)
 *
 * Copyright (c) 2010 - 2019 The OSHI Project Team:
 * https://github.com/oshi/oshi/graphs/contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package oshi.software.os.mac;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Memory; // NOSONAR squid:s1191
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.SystemB;
import com.sun.jna.platform.mac.SystemB.Group;
import com.sun.jna.platform.mac.SystemB.Passwd;
import com.sun.jna.platform.mac.SystemB.ProcTaskAllInfo;
import com.sun.jna.platform.mac.SystemB.ProcTaskInfo;
import com.sun.jna.platform.mac.SystemB.RUsageInfoV2;
import com.sun.jna.platform.mac.SystemB.Timeval;
import com.sun.jna.platform.mac.SystemB.VnodePathInfo;
import com.sun.jna.ptr.IntByReference;

import oshi.software.common.AbstractOperatingSystem;
import oshi.software.os.FileSystem;
import oshi.software.os.NetworkParams;
import oshi.software.os.OSProcess;
import oshi.util.ExecutingCommand;
import oshi.util.ParseUtil;
import oshi.util.platform.mac.SysctlUtil;

/**
 * <p>
 * MacOperatingSystem class.
 * </p>
 */
public class MacOperatingSystem extends AbstractOperatingSystem {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(MacOperatingSystem.class);

    private int maxProc = 1024;

    // 64-bit flag
    private static final int P_LP64 = 0x4;
    /*
     * OS X States:
     */
    private static final int SSLEEP = 1; // sleeping on high priority
    private static final int SWAIT = 2; // sleeping on low priority
    private static final int SRUN = 3; // running
    private static final int SIDL = 4; // intermediate state in process creation
    private static final int SZOMB = 5; // intermediate state in process
                                        // termination
    private static final int SSTOP = 6; // process being traced

    private static final long BOOTTIME;
    static {
        Timeval tv = new Timeval();
        if (!SysctlUtil.sysctl("kern.boottime", tv) || tv.tv_sec.longValue() == 0L) {
            // Usually this works. If it doesn't, fall back to text parsing.
            // Boot time will be the first consecutive string of digits.
            BOOTTIME = ParseUtil.parseLongOrDefault(
                    ExecutingCommand.getFirstAnswer("sysctl -n kern.boottime").split(",")[0].replaceAll("\\D", ""),
                    System.currentTimeMillis() / 1000);
        } else {
            // tv now points to a 64-bit timeval structure for boot time.
            // First 4 bytes are seconds, second 4 bytes are microseconds
            // (we ignore)
            BOOTTIME = tv.tv_sec.longValue();
        }
    }

    /**
     * <p>
     * Constructor for MacOperatingSystem.
     * </p>
     */
    public MacOperatingSystem() {
        this.manufacturer = "Apple";
        this.version = new MacOSVersionInfoEx();
        this.family = ParseUtil.getFirstIntValue(this.version.getVersion()) == 10
                && ParseUtil.getNthIntValue(this.version.getVersion(), 2) >= 12 ? "macOS"
                        : System.getProperty("os.name");
        // Set max processes
        this.maxProc = SysctlUtil.sysctl("kern.maxproc", 0x1000);
        initBitness();
    }

    private void initBitness() {
        if (this.bitness < 64) {
            if (getVersion().getOsxVersionNumber() > 7) {
                this.bitness = 64;
            } else {
                this.bitness = ParseUtil.parseIntOrDefault(ExecutingCommand.getFirstAnswer("getconf LONG_BIT"), 32);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public FileSystem getFileSystem() {
        return new MacFileSystem();
    }

    /** {@inheritDoc} */
    @Override
    public OSProcess[] getProcesses(int limit, ProcessSort sort, boolean slowFields) {
        List<OSProcess> procs = new ArrayList<>();
        int[] pids = new int[this.maxProc];
        int numberOfProcesses = SystemB.INSTANCE.proc_listpids(SystemB.PROC_ALL_PIDS, 0, pids,
                pids.length * SystemB.INT_SIZE) / SystemB.INT_SIZE;
        for (int i = 0; i < numberOfProcesses; i++) {
            // Handle off-by-one bug in proc_listpids where the size returned
            // is: SystemB.INT_SIZE * (pids + 1)
            if (pids[i] == 0) {
                continue;
            }

            OSProcess proc = getProcess(pids[i], slowFields);
            if (proc != null) {
                procs.add(proc);
            }
        }
        List<OSProcess> sorted = processSort(procs, limit, sort);
        return sorted.toArray(new OSProcess[0]);
    }

    /** {@inheritDoc} */
    @Override
    public OSProcess getProcess(int pid) {
        return getProcess(pid, true);
    }

    private OSProcess getProcess(int pid, boolean slowFields) { // NOSONAR squid:S1172
        ProcTaskAllInfo taskAllInfo = new ProcTaskAllInfo();
        if (0 > SystemB.INSTANCE.proc_pidinfo(pid, SystemB.PROC_PIDTASKALLINFO, 0, taskAllInfo, taskAllInfo.size())) {
            return null;
        }
        String name = null;
        String path = "";
        Pointer buf = new Memory(SystemB.PROC_PIDPATHINFO_MAXSIZE);
        if (0 < SystemB.INSTANCE.proc_pidpath(pid, buf, SystemB.PROC_PIDPATHINFO_MAXSIZE)) {
            path = buf.getString(0).trim();
            // Overwrite name with last part of path
            String[] pathSplit = path.split("/");
            if (pathSplit.length > 0) {
                name = pathSplit[pathSplit.length - 1];
            }
        }
        // If process is gone, return null
        if (taskAllInfo.ptinfo.pti_threadnum < 1) {
            return null;
        }
        if (name == null) {
            // pbi_comm contains first 16 characters of name
            // null terminated
            for (int t = 0; t < taskAllInfo.pbsd.pbi_comm.length; t++) {
                if (taskAllInfo.pbsd.pbi_comm[t] == 0) {
                    name = new String(taskAllInfo.pbsd.pbi_comm, 0, t);
                    break;
                }
            }
        }
        long bytesRead = 0;
        long bytesWritten = 0;
        if (getVersion().getOsxVersionNumber() >= 9) {
            RUsageInfoV2 rUsageInfoV2 = new RUsageInfoV2();
            if (0 == SystemB.INSTANCE.proc_pid_rusage(pid, SystemB.RUSAGE_INFO_V2, rUsageInfoV2)) {
                bytesRead = rUsageInfoV2.ri_diskio_bytesread;
                bytesWritten = rUsageInfoV2.ri_diskio_byteswritten;
            }
        }
        long now = System.currentTimeMillis();
        OSProcess proc = new OSProcess();
        proc.setName(name);
        proc.setPath(path);
        switch (taskAllInfo.pbsd.pbi_status) {
        case SSLEEP:
            proc.setState(OSProcess.State.SLEEPING);
            break;
        case SWAIT:
            proc.setState(OSProcess.State.WAITING);
            break;
        case SRUN:
            proc.setState(OSProcess.State.RUNNING);
            break;
        case SIDL:
            proc.setState(OSProcess.State.NEW);
            break;
        case SZOMB:
            proc.setState(OSProcess.State.ZOMBIE);
            break;
        case SSTOP:
            proc.setState(OSProcess.State.STOPPED);
            break;
        default:
            proc.setState(OSProcess.State.OTHER);
            break;
        }
        proc.setProcessID(pid);
        proc.setParentProcessID(taskAllInfo.pbsd.pbi_ppid);
        proc.setUserID(Integer.toString(taskAllInfo.pbsd.pbi_uid));
        Passwd user = SystemB.INSTANCE.getpwuid(taskAllInfo.pbsd.pbi_uid);
        proc.setUser(user == null ? proc.getUserID() : user.pw_name);
        proc.setGroupID(Integer.toString(taskAllInfo.pbsd.pbi_gid));
        Group group = SystemB.INSTANCE.getgrgid(taskAllInfo.pbsd.pbi_gid);
        proc.setGroup(group == null ? proc.getGroupID() : group.gr_name);
        proc.setThreadCount(taskAllInfo.ptinfo.pti_threadnum);
        proc.setPriority(taskAllInfo.ptinfo.pti_priority);
        proc.setVirtualSize(taskAllInfo.ptinfo.pti_virtual_size);
        proc.setResidentSetSize(taskAllInfo.ptinfo.pti_resident_size);
        proc.setKernelTime(taskAllInfo.ptinfo.pti_total_system / 1000000L);
        proc.setUserTime(taskAllInfo.ptinfo.pti_total_user / 1000000L);
        proc.setStartTime(taskAllInfo.pbsd.pbi_start_tvsec * 1000L + taskAllInfo.pbsd.pbi_start_tvusec / 1000L);
        proc.setUpTime(now - proc.getStartTime());
        proc.setBytesRead(bytesRead);
        proc.setBytesWritten(bytesWritten);
        proc.setCommandLine(getCommandLine(pid));
        proc.setOpenFiles(taskAllInfo.pbsd.pbi_nfiles);
        proc.setBitness((taskAllInfo.pbsd.pbi_flags & P_LP64) == 0 ? 32 : 64);

        VnodePathInfo vpi = new VnodePathInfo();
        if (0 < SystemB.INSTANCE.proc_pidinfo(pid, SystemB.PROC_PIDVNODEPATHINFO, 0, vpi, vpi.size())) {
            int len = 0;
            for (byte b : vpi.pvi_cdir.vip_path) {
                if (b == 0) {
                    break;
                }
                len++;
            }
            proc.setCurrentWorkingDirectory(new String(vpi.pvi_cdir.vip_path, 0, len, StandardCharsets.US_ASCII));
        }
        return proc;
    }

    /** {@inheritDoc} */
    @Override
    public OSProcess[] getChildProcesses(int parentPid, int limit, ProcessSort sort) {
        List<OSProcess> procs = new ArrayList<>();
        int[] pids = new int[this.maxProc];
        int numberOfProcesses = SystemB.INSTANCE.proc_listpids(SystemB.PROC_ALL_PIDS, 0, pids,
                pids.length * SystemB.INT_SIZE) / SystemB.INT_SIZE;
        for (int i = 0; i < numberOfProcesses; i++) {
            // Handle off-by-one bug in proc_listpids where the size returned
            // is: SystemB.INT_SIZE * (pids + 1)
            if (pids[i] == 0) {
                continue;
            }
            if (parentPid == getParentProcessPid(pids[i])) {
                OSProcess proc = getProcess(pids[i], true);
                if (proc != null) {
                    procs.add(proc);
                }
            }
        }
        List<OSProcess> sorted = processSort(procs, limit, sort);
        return sorted.toArray(new OSProcess[0]);
    }

    private int getParentProcessPid(int pid) {
        ProcTaskAllInfo taskAllInfo = new ProcTaskAllInfo();
        if (0 > SystemB.INSTANCE.proc_pidinfo(pid, SystemB.PROC_PIDTASKALLINFO, 0, taskAllInfo, taskAllInfo.size())) {
            return 0;
        }
        return taskAllInfo.pbsd.pbi_ppid;
    }

    private String getCommandLine(int pid) {
        // Get command line via sysctl
        int[] mib = new int[3];
        mib[0] = 1; // CTL_KERN
        mib[1] = 49; // KERN_PROCARGS2
        mib[2] = pid;
        // Allocate memory for arguments
        int argmax = SysctlUtil.sysctl("kern.argmax", 0);
        Pointer procargs = new Memory(argmax);
        IntByReference size = new IntByReference(argmax);
        // Fetch arguments
        if (0 != SystemB.INSTANCE.sysctl(mib, mib.length, procargs, size, null, 0)) {
            LOG.warn(
                    "Failed syctl call for process arguments (kern.procargs2), process {} may not exist. Error code: {}",
                    pid, Native.getLastError());
            return "";
        }
        // Procargs contains an int representing total # of args, followed by a
        // null-terminated execpath string and then the arguments, each
        // null-terminated (possible multiple consecutive nulls),
        // The execpath string is also the first arg.
        int nargs = procargs.getInt(0);
        // Sanity check
        if (nargs < 0 || nargs > 1024) {
            LOG.error("Nonsensical number of process arguments for pid {}: {}", pid, nargs);
            return "";
        }
        List<String> args = new ArrayList<>(nargs);
        // Skip first int (containing value of nargs)
        long offset = SystemB.INT_SIZE;
        // Skip exec_command
        offset += procargs.getString(offset).length();
        // Iterate character by character using offset
        // Build each arg and add to list
        while (nargs-- > 0 && offset < size.getValue()) {
            // Advance through additional nulls
            while (procargs.getByte(offset) == 0) {
                if (++offset >= size.getValue()) {
                    break;
                }
            }
            // Grab a string. This should go until the null terminator
            String arg = procargs.getString(offset);
            args.add(arg);
            // Advance offset to next null
            offset += arg.length();
        }
        // Return args null-delimited
        return String.join("\0", args);
    }

    /** {@inheritDoc} */
    @Override
    public MacOSVersionInfoEx getVersion() {
        return (MacOSVersionInfoEx) this.version;
    }

    /** {@inheritDoc} */
    @Override
    public int getProcessId() {
        return SystemB.INSTANCE.getpid();
    }

    /** {@inheritDoc} */
    @Override
    public int getProcessCount() {
        return SystemB.INSTANCE.proc_listpids(SystemB.PROC_ALL_PIDS, 0, null, 0) / SystemB.INT_SIZE;
    }

    /** {@inheritDoc} */
    @Override
    public int getThreadCount() {
        // Get current pids, then slightly pad in case new process starts while
        // allocating array space
        int[] pids = new int[getProcessCount() + 10];
        int numberOfProcesses = SystemB.INSTANCE.proc_listpids(SystemB.PROC_ALL_PIDS, 0, pids, pids.length)
                / SystemB.INT_SIZE;
        int numberOfThreads = 0;
        ProcTaskInfo taskInfo = new ProcTaskInfo();
        for (int i = 0; i < numberOfProcesses; i++) {
            SystemB.INSTANCE.proc_pidinfo(pids[i], SystemB.PROC_PIDTASKINFO, 0, taskInfo, taskInfo.size());
            numberOfThreads += taskInfo.pti_threadnum;
        }
        return numberOfThreads;
    }

    /** {@inheritDoc} */
    @Override
    public long getSystemUptime() {
        return System.currentTimeMillis() / 1000 - BOOTTIME;
    }

    /** {@inheritDoc} */
    @Override
    public long getSystemBootTime() {
        return BOOTTIME;
    }

    /** {@inheritDoc} */
    @Override
    public NetworkParams getNetworkParams() {
        return new MacNetworkParams();
    }

}
