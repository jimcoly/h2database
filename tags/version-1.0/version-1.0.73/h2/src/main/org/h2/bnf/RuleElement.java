/*
 * Copyright 2004-2008 H2 Group. Multiple-Licensed under the H2 License, 
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.bnf;

import java.util.HashMap;

import org.h2.util.StringUtils;

/**
 * A single terminal rule in a BNF object.
 */
public class RuleElement implements Rule {

    private boolean keyword;
    private String name;
    private Rule link;
    private int type;
    private String topic;

    RuleElement(String name, String topic) {
        this.name = name;
        this.topic = topic;
        if (name.length() == 1 || name.equals(StringUtils.toUpperEnglish(name))) {
            keyword = true;
        }
        topic = StringUtils.toLowerEnglish(topic);
        this.type = topic.startsWith("function") ? Sentence.FUNCTION : Sentence.KEYWORD;
    }
    
    public String toString() {
        return name;
    }

    RuleElement merge(RuleElement rule) {
        return new RuleElement(name + " " + rule.name, topic);
    }

    public String random(Bnf config, int level) {
        if (keyword) {
            return name.length() > 1 ? " " + name + " " : name;
        }
        if (link != null) {
            return link.random(config, level + 1);
        }
        throw new Error(">>>" + name + "<<<");
    }

    public String name() {
        return name;
    }

    public Rule last() {
        return this;
    }

    public void setLinks(HashMap ruleMap) {
        if (link != null) {
            link.setLinks(ruleMap);
        }
        if (keyword) {
            return;
        }
        for (int i = 0; i < name.length() && link == null; i++) {
            String test = StringUtils.toLowerEnglish(name.substring(i));
            RuleHead r = (RuleHead) ruleMap.get(test);
            if (r != null) {
                link = r.getRule();
                return;
            }
        }
        if (link == null) {
            throw new Error(">>>" + name + "<<<");
        }
    }

    public boolean matchRemove(Sentence sentence) {
        if (sentence.stop()) {
            return false;
        }
        String query = sentence.query;
        if (query.length() == 0) {
            return false;
        }
        if (keyword) {
            String up = sentence.queryUpper;
            if (up.startsWith(name)) {
                query = query.substring(name.length());
                while (!"_".equals(name) && query.length() > 0 && Character.isWhitespace(query.charAt(0))) {
                    query = query.substring(1);
                }
                sentence.setQuery(query);
                return true;
            }
            return false;
        }
        if (!link.matchRemove(sentence)) {
            return false;
        }
        if (name != null && !name.startsWith("@") && (link.name() == null || !link.name().startsWith("@"))) {
            query = sentence.query;
            while (query.length() > 0 && Character.isWhitespace(query.charAt(0))) {
                query = query.substring(1);
            }
            sentence.setQuery(query);
        }
        return true;
    }

    public void addNextTokenList(Sentence sentence) {
        if (sentence.stop()) {
            return;
        }
        if (keyword) {
            String query = sentence.query;
            String q = query.trim();
            String up = sentence.queryUpper.trim();
            if (q.length() == 0 || name.startsWith(up)) {
                if (q.length() < name.length()) {
                    sentence.add(name, name.substring(q.length()), type);
                }
            }
            return;
        }
        link.addNextTokenList(sentence);
    }

}