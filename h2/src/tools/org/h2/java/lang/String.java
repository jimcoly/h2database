/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.java.lang;

import org.h2.java.Ignore;
import org.h2.java.Local;

/* c:

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>
#include <stdint.h>

#define jvoid void
#define jboolean int8_t
#define jbyte int8_t
#define jchar wchar_t
#define jint int32_t
#define jlong int64_t
#define jfloat float
#define jdouble double
#define ujint uint32_t
#define ujlong uint64_t
#define true 1
#define false 0
#define null 0

#define STRING(s) ptr<java_lang_String>(new java_lang_String(ptr< array<jchar> >(new array<jchar>(s, (jint) wcslen(s)))));

// #define STRING(s) new java_lang_String(new array<jchar>(s, (jint) wcslen(s)));

class RefBase {
protected:
    jint refCount;
public:
    RefBase() {
        refCount = 0;
    }
    void reference() {
        refCount++;
    }
    void release() {
        if (--refCount == 0) {
            delete this;
        }
    }
    virtual ~RefBase() {
    }
};
template <class T> class ptr {
    T* pointer;
public:
    explicit ptr(T* p=0) : pointer(p) {
        if (p != 0) {
            ((RefBase*)p)->reference();
        }
    }
    ptr(const ptr<T>& p) : pointer(p.pointer) {
        if (p.pointer != 0) {
            ((RefBase*)p.pointer)->reference();
        }
    }
    ~ptr() {
        if (pointer != 0) {
            ((RefBase*)pointer)->release();
        }
    }
    ptr<T>& operator= (const ptr<T>& p) {
        if (this != &p && pointer != p.pointer) {
            if (pointer != 0) {
                ((RefBase*)pointer)->release();
            }
            pointer = p.pointer;
            if (pointer != 0) {
                ((RefBase*)pointer)->reference();
            }
        }
        return *this;
    }
    T& operator*() {
        return *pointer;
    }
    T* operator->() {
        return pointer;
    }
    jboolean operator==(const ptr<T>& p) {
        return pointer == p->pointer;
    }
    jboolean operator==(const RefBase* t) {
        return pointer == t;
    }
};
template <class T> class array : RefBase {
    jint len;
    T* data;
public:
    array(const T* d, jint len) {
        this->len = len;
        data = new T[len];
        memcpy(data, d, sizeof(T) * len);
    }
    array(jint len) {
        this->len = len;
        data = new T[len];
    }
    ~array() {
        delete[] data;
    }
    T* getData() {
        return data;
    }
    jint length() {
        return len;
    }
    T& operator[](jint index) {
        if (index < 0 || index >= len) {
            throw "index set";
        }
        return data[index];
    }
    T& at(jint index) {
        if (index < 0 || index >= len) {
            throw "index set";
        }
        return data[index];
    }
};

*/

/**
 * A java.lang.String implementation.
 */
public class String {

    /**
     * The character array.
     */
    @Local
    char[] chars;

    private int hash;

    public String(char[] chars) {
        this.chars = new char[chars.length];
        System.arraycopy(chars, 0, this.chars, 0, chars.length);
    }

    public String(char[] chars, int offset, int count) {
        this.chars = new char[count];
        System.arraycopy(chars, offset, this.chars, 0, count);
    }

    public int hashCode() {
        int h = hash;
        if (h == 0) {
            int size = chars.length;
            if (size != 0) {
                for (int i = 0; i < size; i++) {
                    h = h * 31 + chars[i];
                }
                hash = h;
            }
        }
        return h;
    }

    /**
     * Get the length of the string.
     *
     * @return the length
     */
    public int length() {
        return chars.length;
    }

    public String toStringMethod() {
        return this;
    }

    @Ignore
    public java.lang.String asString() {
        return new java.lang.String(chars);
    }

    @Ignore
    public static String wrap(java.lang.String x) {
        return new String(x.toCharArray());
    }

}