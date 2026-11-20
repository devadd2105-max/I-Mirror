package com.devadd.imirror.core;

import java.io.IOException;

/** Where the web page files come from (Android assets in the app, a folder in the tests). */
public interface AssetSource {
    byte[] read(String name) throws IOException;
}
