package org.opencb.opencga.catalog.models.acls;

import org.opencb.commons.datastore.core.ObjectMap;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by pfurio on 11/05/16.
 */
public class IndividualAcl extends AbstractAcl<IndividualAcl.IndividualPermissions> {

    public enum IndividualPermissions {
        VIEW,
        UPDATE,
        DELETE,
        SHARE,
        CREATE_ANNOTATIONS,
        VIEW_ANNOTATIONS,
        UPDATE_ANNOTATIONS,
        DELETE_ANNOTATIONS
    }

    public IndividualAcl() {
        this("", Collections.emptyList());
    }

    public IndividualAcl(String member, EnumSet<IndividualPermissions> permissions) {
        super(member, permissions);
    }

    public IndividualAcl(String member, ObjectMap permissions) {
        super(member, EnumSet.noneOf(IndividualPermissions.class));

        EnumSet<IndividualPermissions> aux = EnumSet.allOf(IndividualPermissions.class);
        for (IndividualPermissions permission : aux) {
            if (permissions.containsKey(permission.name()) && permissions.getBoolean(permission.name())) {
                this.permissions.add(permission);
            }
        }
    }

    public IndividualAcl(String member, List<String> permissions) {
        super(member, EnumSet.noneOf(IndividualPermissions.class));
        if (permissions.size() > 0) {
            this.permissions.addAll(permissions.stream().map(IndividualPermissions::valueOf).collect(Collectors.toList()));
        }
    }

}
