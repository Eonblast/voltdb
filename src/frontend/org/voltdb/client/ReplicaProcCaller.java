/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.client;

import java.io.IOException;

public interface ReplicaProcCaller {
    /**
     * Asynchronously invoke a replicated procedure. Does not guarantee that the
     * invocation is actually queued. If there is backpressure on all
     * connections to the cluster then the invocation will not be queued. Check
     * the return value to determine if queuing actually took place.
     *
     * @param callback ProcedureCallback that will be invoked with procedure results.
     * @param procName class name (not qualified by package) of the procedure to execute.
     * @param parameters vararg list of procedure's parameter values.
     * @return <code>true</code> if the procedure was queued and
     *         <code>false</code> otherwise
     */
    public boolean callProcedure(
            long originalTxnId,
            ProcedureCallback callback,
            String procName,
            Object... parameters)
            throws IOException, NoConnectionsException;
}
