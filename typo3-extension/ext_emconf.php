<?php

$EM_CONF[$_EXTKEY] = [
    'title' => 'Solr Semantic More Like This',
    'description' => 'Hybrid semantic + lexical similar content for TYPO3, powered by Apache Solr SMLT plugin',
    'category' => 'plugin',
    'author' => 'dkd Internet Service GmbH',
    'author_email' => 'info@dkd.de',
    'state' => 'beta',
    'version' => '0.1.0',
    'constraints' => [
        'depends' => [
            'typo3' => '13.4.0-13.4.99',
            'solr' => '13.1.0-13.1.99',
        ],
        'conflicts' => [],
        'suggests' => [],
    ],
];
