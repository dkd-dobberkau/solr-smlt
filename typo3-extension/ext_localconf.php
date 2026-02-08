<?php

declare(strict_types=1);

use TYPO3\CMS\Extbase\Utility\ExtensionUtility;

defined('TYPO3') or die();

ExtensionUtility::configurePlugin(
    'SolrSemanticMlt',
    'SimilarContent',
    [\Dkd\SolrSemanticMlt\Controller\SimilarContentController::class => 'show'],
    [],
    ExtensionUtility::PLUGIN_TYPE_CONTENT_ELEMENT,
);
