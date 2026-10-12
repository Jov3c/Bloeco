package com.blocke.bloeco.api;

/** Opaque account handle issued by Bloeco; consumers must not implement or forge handles. */
public interface InstitutionAccount {
    String purpose();
}
