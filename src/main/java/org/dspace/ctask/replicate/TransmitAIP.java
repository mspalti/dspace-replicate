/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.ctask.replicate;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import org.dspace.authorize.AuthorizeException;
import org.dspace.content.DSpaceObject;
import org.dspace.curate.AbstractCurationTask;
import org.dspace.curate.Curator;
import org.dspace.curate.Suspendable;
import org.dspace.pack.Packer;
import org.dspace.pack.PackerFactory;

/**
 * TransmitAIP task creates an AIP suitable for replication, and forwards it
 * to the replication system for transmission (upload).
 * <P>
 * The type of AIP produced is based on the 'packer.pkgtype' setting
 * in 'replicate.cfg'. See the org.dspace.pack.PackerFactory for more info.
 * <P>
 * This task is "suspendable" when invoked from the UI. If a single AIP fails
 * to be generated and transmitted to storage, we should inform the user ASAP.
 * We wouldn't want them to assume everything was transferred successfully, 
 * if there were actually underlying errors.
 * <P>
 * Note that this task has a companion task called TransmitSingleAIP which
 * ensures that no child/member objects are transmitted.
 * 
 * @author richardrodgers
 * @see PackerFactory
 * @see TransmitSingleAIP
 */
@Suspendable(invoked=Curator.Invoked.INTERACTIVE)
public class TransmitAIP extends AbstractCurationTask
{
    // Group where all AIPs will be stored
    private String storeGroupName;
    private String skipList;

    @Override
    public void init(Curator curator, String taskId) throws IOException {
        super.init(curator, taskId);
        storeGroupName = configurationService.getProperty("replicate.group.aip.name");
        skipList = configurationService.getProperty("replicate.transmitaip.skiplist");
    }


    /**
     * Perform 'Transmit AIP' task
     * <p>
     * Actually generates the AIP and transmits it to the replica ObjectStore
     * @param dso DSpace Object to perform on
     * @return integer which represents Curator return status
     * @throws IOException if I/O error
     */
    @Override
    public int perform(DSpaceObject dso) throws IOException
    {
        if (skipList != null) {
            List<String> skipIds = createSkipList(skipList);
            for (String id : skipIds) {
                if (id.trim().contentEquals(dso.getHandle())) {
                    String msg = "This item is in the replicate skiplist: " + dso.getHandle();
                    setResult(msg);
                    report(msg);
                    return Curator.CURATE_SKIP;
                }
            }
        }

        ReplicaManager repMan = ReplicaManager.instance();
            
        Packer packer = PackerFactory.instance(dso);
        try
        {
            File archive = packer.pack(repMan.stage(storeGroupName, dso.getHandle()));
            long size = repMan.transferObject(storeGroupName, archive);
            // The DuraCloud transmission failed. Currently, curate tasks
            // suspend processing if either CURATE_FAIL or CURATE_ERROR are returned.
            if (size == -1) {
                String result = "Transmission to DuraCloud failed for:" + dso.getHandle();
                setResult(result);
                report(result);
                return Curator.CURATE_UNSET;
            }
            // File was not transmitted to DuraCloud because the checksums matched.
            // (For local object stores the size will always be non-zero.)
            else if (size == 0L) {
                setResult("Checksum matched. New AIP was not transmitted for " + dso.getHandle());
                return Curator.CURATE_SUCCESS;
            }
            else {
                String successMsg = "Created AIP: '" + archive.getName() +
                    "' size: " + archive.length();
                setResult(successMsg);
                return Curator.CURATE_SUCCESS;
            }
        }
        catch (AuthorizeException authE)
        {
            throw new IOException(authE);
        }
        catch (SQLException sqlE)
        {
            throw new IOException(sqlE);
        }
    }

    private List<String> createSkipList(String skipList) {
        String[] skip;
        skip = skipList.split(",");
        return Arrays.asList(skip);

    }
}
