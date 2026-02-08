<?php

declare(strict_types=1);

use TYPO3\CMS\Core\Utility\ExtensionManagementUtility;

defined('TYPO3') or die();

ExtensionManagementUtility::addStaticFile(
    'solr_semantic_mlt',
    'Configuration/TypoScript/',
    'Solr Semantic More Like This',
);
