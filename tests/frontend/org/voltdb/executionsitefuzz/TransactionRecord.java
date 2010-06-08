/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.executionsitefuzz;

public class TransactionRecord
{
    long m_txnId;
    boolean m_closed;
    boolean m_rollback;
    boolean m_multipart;
    boolean m_selfFail;
    boolean m_otherFail;

    TransactionRecord(LogString logString)
    {
        assert(logString.isTxnStart());
        m_txnId = logString.getTxnId();
        m_closed = false;
        m_rollback = false;
        m_multipart = logString.isMultiPart();
        m_selfFail = false;
        m_otherFail = false;
    }

    void updateRecord(LogString logString)
    {
        assert(!logString.isTxnStart());
        assert(!isClosed());
        if (logString.isRollback())
        {
            assert(logString.getTxnId() == m_txnId);
            m_rollback = true;
        }
        else if (logString.isSelfFault())
        {
            m_selfFail = true;
        }
        else if (logString.isOtherFault())
        {
            // XXX future add record of other failure site ID
            m_otherFail = true;
        }
        else if (logString.isTxnEnd())
        {
            assert(logString.getTxnId() == m_txnId);
            m_closed = true;
        }
    }

    Long getTxnId()
    {
        return m_txnId;
    }

    boolean isMultiPart()
    {
        return m_multipart;
    }

    boolean isClosed()
    {
        return m_closed;
    }

    boolean rolledBack()
    {
        return m_rollback;
    }

    boolean failed()
    {
        return m_selfFail;
    }

    boolean sawFailure()
    {
        return m_otherFail;
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("TXN: ").append(m_txnId);
        sb.append("  Type: ").append(m_multipart ? "multi" : "single");
        sb.append("  Rollback: ").append(m_rollback).append(", Closed: ").append(m_closed);
        sb.append("  Self-fail: ").append(m_selfFail);
        sb.append("  Saw failures: ").append(m_otherFail);
        return sb.toString();
    }

    public boolean equals(Object o)
    {
        if (o == this)
        {
            return true;
        }
        if (!(o instanceof TransactionRecord))
        {
            return false;
        }
        boolean retval = true;
        TransactionRecord other = (TransactionRecord) o;
        retval &= (other.m_txnId == m_txnId);
        retval &= (other.m_closed == m_closed);
        retval &= (other.m_multipart == m_multipart);
        retval &= (other.m_rollback == m_rollback);
        return retval;
    }
}