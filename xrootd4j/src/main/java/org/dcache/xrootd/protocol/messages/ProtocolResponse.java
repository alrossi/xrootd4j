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

import org.dcache.xrootd.protocol.XrootdProtocol;

public class ProtocolResponse extends AbstractXrootdResponse<ProtocolRequest>
{
    private final int flags;

    public ProtocolResponse(ProtocolRequest request, int flags)
    {
        super(request, XrootdProtocol.kXR_ok);
        this.flags = flags;
    }

    public int getFlags()
    {
        return flags;
    }

    @Override
    public int getDataLength()
    {
        return 8;
    }

    @Override
    protected void getBytes(ByteBuf buffer)
    {
        buffer.writeInt(XrootdProtocol.PROTOCOL_VERSION);
        buffer.writeInt(flags);
    }

    @Override
    public String toString()
    {
        return String.format("protocol-response[%d]", flags);
    }
}
