<?php

declare(strict_types=1);

use TYPO3\CMS\Core\Utility\ExtensionManagementUtility;
use TYPO3\CMS\Extbase\Utility\ExtensionUtility;

defined('TYPO3') or die();

ExtensionUtility::registerPlugin(
    'SolrSemanticMlt',
    'SimilarContent',
    'LLL:EXT:solr_semantic_mlt/Resources/Private/Language/locallang.xlf:plugin.similarcontent.title',
    'content-solr-smlt',
    'plugins',
    'LLL:EXT:solr_semantic_mlt/Resources/Private/Language/locallang.xlf:plugin.similarcontent.description',
);

$GLOBALS['TCA']['tt_content']['types']['solrsemanticmlt_similarcontent']['showitem'] = '
    --div--;LLL:EXT:core/Resources/Private/Language/locallang_general.xlf:LGL.general,
        --palette--;;general,
        --palette--;;headers,
    --div--;LLL:EXT:solr_semantic_mlt/Resources/Private/Language/locallang.xlf:plugin.tab.settings,
        pi_flexform,
    --div--;LLL:EXT:core/Resources/Private/Language/locallang_general.xlf:LGL.access,
        --palette--;;hidden,
        --palette--;;access,
';

ExtensionManagementUtility::addPiFlexFormValue(
    '*',
    'FILE:EXT:solr_semantic_mlt/Configuration/FlexForms/SimilarContent.xml',
    'solrsemanticmlt_similarcontent',
);
