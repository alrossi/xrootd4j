/**
 * Copyright (C) 2011-2018 dCache.org <support@dcache.org>
 *
 * This file is part of xrootd4j.
 *
 * xrootd4j is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * xrootd4j is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with xrootd4j.  If not, see http://www.gnu.org/licenses/.
 */
package org.dcache.xrootd.protocol.messages;

import io.netty.buffer.ByteBuf;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.dcache.xrootd.protocol.XrootdProtocol.*;
import static org.dcache.xrootd.security.XrootdSecurityProtocol.kXR_secPedantic;

public class PrepareRequest extends AbstractXrootdRequest
{
    private final int options;
    private final int priority;
    private final String[] plist;

    public PrepareRequest(ByteBuf buffer)
    {
        super(buffer, kXR_prepare);
        signingLevel = kXR_secPedantic;
        options = buffer.getUnsignedShort(4);
        priority = buffer.getUnsignedShort(5);

        int plen = buffer.getInt(20);
        int end = 24 + plen;

        plist = buffer.toString(24, end - 24, US_ASCII).split("\n");
    }

    public int getOptions()
    {
        return options;
    }

    public int getPriority()
    {
        return priority;
    }

    public String[] getPathList()
    {
        return plist;
    }

    public boolean isCancel() {
        return (getOptions() & kXR_cancel) == kXR_cancel;
    }

    public boolean isNotify() {
        return (getOptions() & kXR_notify) == kXR_notify;
    }

    public boolean isNoErrors() {
        return (getOptions() & kXR_noerrs) == kXR_noerrs;
    }

    public boolean isStage() {
        return (getOptions() & kXR_stage) == kXR_stage;
    }

    public boolean isWriteMode() {
        return (getOptions() & kXR_wmode) == kXR_wmode;
    }

    public boolean isColocate() {
        return (getOptions() & kXR_coloc) == kXR_coloc;
    }

    public boolean isFresh() {
        return (getOptions() & kXR_fresh) == kXR_fresh;
    }

    @Override
    public String toString()
    {
        return String.format("prepare[%d,%d,%s]", options, priority,
                             Arrays.toString(plist));
    }
}
