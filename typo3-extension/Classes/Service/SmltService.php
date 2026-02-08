<?php

declare(strict_types=1);

namespace Dkd\SolrSemanticMlt\Service;

use ApacheSolrForTypo3\Solr\ConnectionManager;
use Psr\Http\Client\ClientExceptionInterface;
use Psr\Http\Client\ClientInterface;
use Psr\Http\Message\RequestFactoryInterface;
use Psr\Log\LoggerInterface;

class SmltService
{
    public function __construct(
        private readonly ConnectionManager $connectionManager,
        private readonly RequestFactoryInterface $requestFactory,
        private readonly ClientInterface $httpClient,
        private readonly LoggerInterface $logger,
    ) {}

    /**
     * Find documents similar to the given document ID using the SMLT Solr handler.
     *
     * @return array{sourceId: string, mode: string, numFound: int, docs: array<int, array<string, mixed>>}
     */
    public function findSimilar(
        string $documentId,
        int $siteRootPageId,
        int $languageId = 0,
        int $count = 5,
        string $mode = 'hybrid',
        float $vectorWeight = 0.7,
        float $mltWeight = 0.3,
    ): array {
        try {
            $connection = $this->connectionManager
                ->getConnectionByRootPageId($siteRootPageId, $languageId);

            $endpoint = $connection->getEndpoint('read');
            $baseUri = rtrim((string)$endpoint->getCoreBaseUri(), '/');

            $params = http_build_query([
                'smlt' => 'true',
                'smlt.id' => $documentId,
                'smlt.count' => $count,
                'smlt.mode' => $mode,
                'smlt.vectorWeight' => $vectorWeight,
                'smlt.mltWeight' => $mltWeight,
                'q' => '*:*',
                'rows' => 0,
                'wt' => 'json',
            ]);

            $url = $baseUri . '/smlt?' . $params;
            $request = $this->requestFactory->createRequest('GET', $url);

            $authentication = $endpoint->getAuthentication();
            if ($authentication !== null && isset($authentication['username'], $authentication['password'])) {
                $credentials = base64_encode($authentication['username'] . ':' . $authentication['password']);
                $request = $request->withHeader('Authorization', 'Basic ' . $credentials);
            }

            $response = $this->httpClient->sendRequest($request);

            if ($response->getStatusCode() !== 200) {
                $this->logger->warning('SMLT request returned HTTP {status}', [
                    'status' => $response->getStatusCode(),
                    'documentId' => $documentId,
                ]);
                return $this->emptyResponse($documentId, $mode);
            }

            $body = json_decode((string)$response->getBody(), true);
            if (!is_array($body) || !isset($body['semanticMoreLikeThis'])) {
                return $this->emptyResponse($documentId, $mode);
            }

            return $body['semanticMoreLikeThis'];
        } catch (ClientExceptionInterface $e) {
            $this->logger->error('SMLT request failed: {message}', [
                'message' => $e->getMessage(),
                'documentId' => $documentId,
                'exception' => $e,
            ]);
            return $this->emptyResponse($documentId, $mode);
        }
    }

    /**
     * @return array{sourceId: string, mode: string, numFound: int, docs: list<never>}
     */
    private function emptyResponse(string $documentId, string $mode): array
    {
        return [
            'sourceId' => $documentId,
            'mode' => $mode,
            'numFound' => 0,
            'docs' => [],
        ];
    }
}
