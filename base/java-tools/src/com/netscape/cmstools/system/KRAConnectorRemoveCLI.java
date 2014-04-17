// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2013 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package com.netscape.cmstools.system;

import java.util.Arrays;

import com.netscape.cmstools.cli.CLI;
import com.netscape.cmstools.cli.MainCLI;

/**
 * @author Ade Lee
 */
public class KRAConnectorRemoveCLI extends CLI {

    public KRAConnectorCLI kraConnectorCLI;

    public KRAConnectorRemoveCLI(KRAConnectorCLI kraConnectorCLI) {
        super("del", "Remove KRA connector from CA", kraConnectorCLI);
        this.kraConnectorCLI = kraConnectorCLI;
    }

    public void printHelp() {
        formatter.printHelp(getFullName() + " <KRA Host> <KRA Port>", options);
    }

    public void execute(String[] args) throws Exception {

        // Check for "--help"
        if (Arrays.asList(args).contains("--help")) {
            // Display usage
            printHelp();
            System.exit(0);
        }

        if (args.length != 2) {
            printHelp();
            System.exit(1);
        }

        String kraHost = args[0];
        String kraPort = args[1];

        kraConnectorCLI.kraConnectorClient.removeConnector(kraHost, kraPort);

        MainCLI.printMessage("Removed KRA connector \""+kraHost + ":" + kraPort +"\"");
    }
}
