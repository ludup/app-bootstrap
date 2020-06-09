package com.hypersocket.bootstrap;

import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandlerFactory;

public class BootstrapClassLoader extends URLClassLoader {

	public BootstrapClassLoader(URL[] arg0) {
		super(arg0);
	}

	public BootstrapClassLoader(URL[] arg0, ClassLoader arg1) {
		super(arg0, arg1);
	}

	public BootstrapClassLoader(URL[] arg0, ClassLoader arg1,
			URLStreamHandlerFactory arg2) {
		super(arg0, arg1, arg2);
	}

}
