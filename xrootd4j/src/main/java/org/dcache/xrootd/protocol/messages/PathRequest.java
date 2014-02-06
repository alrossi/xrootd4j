/**
 * Copyright (C) 2011-2013 dCache.org <support@dcache.org>
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
 * License along with xrootd4j.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.dcache.xrootd.protocol.messages;

import org.dcache.xrootd.protocol.XrootdProtocol;
import org.jboss.netty.buffer.ChannelBuffer;

/**
 * Base class for requests that contain a path.
 *
 * The path and opaque data is found at offset 24 in the message, with
 * the length at offset 20. The path and opaque data are delimited by
 * a question mark.
 */
public class PathRequest extends XrootdRequest
{
    private String _path;
    private String _opaque;

    public PathRequest()
    {
        super();
    }

    public PathRequest(ChannelBuffer buffer, int requestId)
    {
        super(buffer, requestId);
        setPathAndOpaque(buffer, 24, buffer.getInt(20));
    }

    private void setPathAndOpaque(ChannelBuffer buffer, int begin, int length)
    {
        int end = begin + length;
        int pos = buffer.indexOf(begin, end, XrootdProtocol.OPAQUE_DELIMITER);
        if (pos > -1) {
            setPath(buffer.toString(begin, pos - begin, XROOTD_CHARSET));
            setOpaque(buffer.toString(pos + 1, end - (pos + 1), XROOTD_CHARSET));
        } else {
            setPath(buffer.toString(begin, end - begin, XROOTD_CHARSET));
            setOpaque("");
        }
    }

    public String getOpaque()
    {
        return _opaque;
    }

    public void setOpaque(String opaque)
    {
        _opaque = opaque;
    }

    public String getPath()
    {
        return _path;
    }

    public void setPath(String path)
    {
        _path = path;
    }
}
